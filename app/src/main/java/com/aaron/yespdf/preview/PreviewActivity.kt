package com.aaron.yespdf.preview

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.FragmentPagerAdapter
import androidx.lifecycle.MutableLiveData
import com.aaron.base.impl.OnClickListenerImpl
import com.aaron.base.impl.TextWatcherImpl
import com.aaron.yespdf.R
import com.aaron.yespdf.common.*
import com.aaron.yespdf.common.bean.PDF
import com.aaron.yespdf.common.event.RecentPDFEvent
import com.aaron.yespdf.common.utils.AboutUtils
import com.aaron.yespdf.common.utils.NotchUtils
import com.aaron.yespdf.common.utils.PdfUtils
import com.aaron.yespdf.settings.SettingsActivity.Companion.start
import com.blankj.utilcode.util.*
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.PDFView.Configurator
import com.google.gson.reflect.TypeToken
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfPasswordException
import com.uber.autodispose.AutoDispose
import com.uber.autodispose.android.lifecycle.AndroidLifecycleScopeProvider
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.app_activity_preview.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.File
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 注意点：显示到界面上的页数需要加 1 ，因为 PDFView 获取到的页数是从 0 计数的。
 */
class PreviewActivity : CommonActivity(), IActivityInterface, View.OnClickListener {

    private lateinit var scales: List<TextView>
    private val scaleLevel: ViewGroup by lazy { findViewById<ViewGroup>(R.id.app_scale_level) }
    private val scale025: TextView by lazy { findViewById<TextView>(R.id.app_scale_0_25) }
    private val scale050: TextView by lazy { findViewById<TextView>(R.id.app_scale_0_50) }
    private val scale075: TextView by lazy { findViewById<TextView>(R.id.app_scale_0_75) }
    private val scale100: TextView by lazy { findViewById<TextView>(R.id.app_scale_1_00) }
    private val scale200: TextView by lazy { findViewById<TextView>(R.id.app_scale_2_00) }
    private val scale300: TextView by lazy { findViewById<TextView>(R.id.app_scale_3_00) }
    private val nightModeBtn: View by lazy { findViewById<View>(R.id.app_night_btn) }
    private val isNightMode: MutableLiveData<Boolean> = MutableLiveData<Boolean>().apply {
        observe(this@PreviewActivity::getLifecycle) {
            Settings.nightMode = it

            if (it) {
                app_pdfview_bg.setImageResource(R.drawable.app_ic_placeholder_white)
                app_pdfview_bg.setBackgroundColor(Color.BLACK)
            } else {
                app_pdfview_bg.setImageResource(R.drawable.app_ic_placeholder_black)
                app_pdfview_bg.setBackgroundColor(Color.WHITE)
            }
            scaleViewParentAnim(0.0f, 0L, object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator?) {
                    scaleLevel.visibility = View.GONE
                    scales.forEach { scaleView -> scaleView.translationX = 0f }
                }
            })

            initPdf(uri, pdf)

            val textColor = if (it) R.color.base_black else R.color.base_white
            val bg = if (it) R.drawable.app_shape_bg_scale_view_white else R.drawable.app_shape_bg_scale_view_black
            scales.forEach { tv ->
                tv.setTextColor(resources.getColor(textColor))
                tv.background = resources.getDrawable(bg)
            }
        }
    }
    private val barShowState: MutableLiveData<Boolean> = MutableLiveData<Boolean>().apply {
        observe(this@PreviewActivity::getLifecycle) {
            if (!Settings.scrollShortCut) {
                return@observe
            }

            val pair: Pair<Float, Animator.AnimatorListener?> = if (it) {
                Pair(0.0f, null)
            } else {
                Pair(1.0f, if (app_pdfview.zoom != 1.0f) {
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationStart(animation: Animator?) {
                            scaleLevel.visibility = View.VISIBLE
                        }
                    }
                } else null)
            }
            scaleViewParentAnim(pair.first, 0L, pair.second)
        }
    }
    private val updateDB: MutableLiveData<Int> = MutableLiveData<Int>().apply {
        observe(this@PreviewActivity::getLifecycle) {
            pdf?.run {
                this.curPage = this@PreviewActivity.curPage
                this.progress = getPercent(curPage + 1, pageCount)
                this.bookmark = GsonUtils.toJson(bookmarkMap.values)
                this.scaleFactor = this@PreviewActivity.scaleFactor
                DBHelper.updatePDF(this)
                DataManager.updatePDFs()
            }
        }
    }
    private val sbScrollLevel: SeekBar by lazy { findViewById<SeekBar>(R.id.app_sb_scroll_level) }
    private val alertDialog: Dialog by lazy(LazyThreadSafetyMode.NONE) {
        DialogManager.createAlertDialog(this) { tvTitle, tvContent, btn ->
            tvTitle.setText(R.string.app_oop_error)
            tvContent.setText(R.string.app_doc_parse_error)
            btn.setText(R.string.app_exit_cur_content)
            btn.setOnClickListener { finish() }
        }
    }
    private val inputDialog: Dialog by lazy(LazyThreadSafetyMode.NONE) {
        DialogManager.createInputDialog(this) { tvTitle, etInput, btnLeft, btnRight ->
            tvTitle.setText(R.string.app_need_verify_password)
            etInput.addTextChangedListener(object : TextWatcherImpl() {
                override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {
                    password = charSequence.toString()
                }
            })
            btnLeft.setText(R.string.app_cancel)
            btnLeft.setOnClickListener { finish() }
            btnRight.setText(R.string.app_confirm)
            btnRight.setOnClickListener {
                initPdf(uri, pdf)
                inputDialog.dismiss()
            }
        }
    }
    private var autoScrollDialog: Dialog? = null

    private var init = true
    private var pdf: PDF? = null // 本应用打开
    private var uri: Uri? = null // 一般是外部应用打开
    private var curPage = 0
    private var pageCount = 0
    private var password: String? = null
    private var isVolumeControl = Settings.volumeControl
    private var contentFragInterface: IContentFragInterface? = null
    private var bkFragInterface: IBkFragInterface? = null
    private var autoDisp: Disposable? = null // 自动滚动
    private var isPause = false
    private var hideBar = false
    private val contentMap: MutableMap<Long, PdfDocument.Bookmark> = HashMap()
    private val bookmarkMap: MutableMap<Long, Bookmark> = HashMap()
    private val pageList: MutableList<Long> = ArrayList()

    private var scaleFactor = 1.0f
    private var isScrollLevelTouchFinish = true
    private var previousPage = 0 // 记录 redo/undo的页码
    private var nextPage = 0
    private var canvas: Canvas? = null // AndroidPDFView 的画布
    private var paint: Paint = Paint() // 画书签的画笔
    private var pageWidth = 0F
    //endregion

    //region Lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initView(savedInstanceState)
        EventBus.getDefault().register(this)

        scales = listOf(scale025, scale050, scale075, scale100, scale200, scale300)
        app_pdfview.curZoom.observe(this::getLifecycle) { scale ->
            if (!Settings.scrollShortCut) {
                return@observe
            }

            fun scaleViewAnim(target: View, value: Float) {
                target.animate().setDuration(SCALE_VIEW_ITEM_ANIM_DURATION).translationX(value).start()
            }

            if (barShowState.value == true) {
                return@observe
            }
            if (Settings.scrollShortCut && scaleLevel.visibility != View.VISIBLE) {
                scaleViewParentAnim(1.0f, 0L, object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator?) {
                        scaleLevel.visibility = View.VISIBLE
                    }
                })
                return@observe
            }
            scales.forEach { scaleViewAnim(it, 0f) }
            if (scale == -1f) return@observe
            when (scale) {
                0.25f -> scale025
                0.50f -> scale050
                0.75f -> scale075
                2.00f -> scale200
                3.00f -> scale300
                else -> null
            }?.apply { scaleViewAnim(this, SCALE_VIEW_ITEM_TRANS_VALUE) }
        }
    }

    override fun onRestart() {
        super.onRestart()
        fixBackToForegroundClick()
        checkScrollShortcut()
    }

    override fun onStart() {
        super.onStart()
        enterFullScreen()
    }

    override fun onResume() {
        super.onResume()
        if (!Settings.scrollShortCut) {
            app_pdfview.apply {
                minZoom = PDFView.DEFAULT_SCALE
                if (app_pdfview.zoom != PDFView.DEFAULT_SCALE) {
                    zoomWithAnimation(minZoom)
                }
            }
        } else {
            app_pdfview.minZoom = PDFView.DEFAULT_MIN_SCALE
        }
    }

    override fun onPause() {
        super.onPause()

        updateDB.value = 0
        // 这里发出事件主要是更新界面阅读进度
        EventBus.getDefault().post(RecentPDFEvent(true))
    }

    override fun onStop() {
        super.onStop()
        hideBar()
        resetUIAndCancelAutoScroll()
        if (autoScrollDialog != null) {
            app_tv_auto_scroll.isSelected = false
            autoScrollDialog?.dismiss()
            autoScrollDialog = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
        app_pdfview.recycle()
    }
    //endregion

    //region Override from system activity
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            if (Settings.keepScreenOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(BUNDLE_CUR_PAGE, curPage)
        outState.putString(BUNDLE_PASSWORD, password)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onBackPressed() {
        when {
            app_ll_content.translationX == 0f -> {
                // 等于 0 表示正处于打开状态，需要隐藏
                hideContent(null)
            }
            app_ll_read_method.translationY != ScreenUtils.getScreenHeight().toFloat() -> {
                // 不等于屏幕高度表示正处于显示状态，需要隐藏
                hideReadMethod()
            }
            else -> {
                super.onBackPressed()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                && autoDisp?.isDisposed == false) {
            exitFullScreen()
            showBar()
            return true
        } else if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                && ScreenUtils.isPortrait() && isVolumeControl && toolbar?.alpha == 0.0f) {
            // 如果非全屏状态是无法使用音量键翻页的
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    var currentPage1 = app_pdfview.currentPage
                    app_pdfview.jumpTo(--currentPage1, true)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    var currentPage2 = app_pdfview.currentPage
                    app_pdfview.jumpTo(++currentPage2, true)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SETTINGS) {
            isVolumeControl = Settings.volumeControl
        }
    }
    //endregion

    //region Implement from CommonActivity
    override fun layoutId(): Int {
        return R.layout.app_activity_preview
    }

    override fun createToolbar(): Toolbar? {
        return findViewById(R.id.app_toolbar)
    }

    override fun isAdaptNotch(): Boolean = true
    //endregion

    //region Implement from interfaces
    override fun onJumpTo(page: Int) {
        app_pdfview_bg.visibility = View.VISIBLE
        hideContent(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                app_pdfview.jumpTo(page)
            }
        })
    }

    /**
     * 处理缩放等级和 View 的动画
     */
    override fun onClick(v: View?) {
        v ?: return
        if (!Settings.scrollShortCut) {
            return
        }

        val scale = when (v.id) {
            R.id.app_scale_0_25 -> 0.25f
            R.id.app_scale_0_50 -> 0.50f
            R.id.app_scale_0_75 -> 0.75f
            R.id.app_scale_1_00 -> 1.00f
            R.id.app_scale_2_00 -> 2.00f
            R.id.app_scale_3_00 -> 3.00f
            else -> 1.00f
        }
        app_pdfview.zoomWithAnimation(scale)
        app_pdfview.curZoom.value = scale
        if (scale == 1.00f) {
            scaleViewParentAnim(0.0f, SCALE_VIEW_ITEM_ANIM_DURATION, object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator?) {
                    scaleLevel.visibility = View.GONE
                    scale100.translationX = 0f
                }
            })
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onNotchEvent(safeInset: NotchUtils.SafeInset) {
        val (left, _, top, _) = safeInset
        toolbar?.apply { setPadding(paddingLeft + left, paddingTop, paddingRight, paddingBottom) }
        app_tab_layout?.apply { setPadding(paddingLeft, paddingTop + top, paddingRight, paddingBottom) }
        app_vp?.apply { setPadding(paddingLeft + left, paddingTop, paddingRight, paddingBottom) }
        app_tv_pageinfo?.apply {
            layoutParams = (layoutParams as ViewGroup.MarginLayoutParams).apply { leftMargin += left }
            requestLayout()
        }
        scaleLevel.apply { setPadding(left, paddingTop, paddingRight, paddingBottom) }
        app_ll_bottombar.apply { setPadding(left, paddingTop, paddingRight, paddingBottom) }
        app_ll_undoredobar.apply {
            layoutParams = (layoutParams as ViewGroup.MarginLayoutParams).apply { leftMargin += left }
            requestLayout()
        }
    }
    //endregion

    //region Private function
    @SuppressLint("SwitchIntDef")
    private fun initView(savedInstanceState: Bundle?) {
        app_ll_undoredobar.apply {
            layoutParams = layoutParams.apply {
                width = if (ScreenUtils.isLandscape()) {
                    ConvertUtils.dp2px(300f)
                } else (ScreenUtils.getScreenWidth() * 0.7f).toInt()
            }
            requestLayout()
        }
        app_ll_content.post {
            app_ll_content.layoutParams.apply {
                val screenWidth = min(ScreenUtils.getScreenHeight(), ScreenUtils.getScreenWidth())
                width = if (Settings.lockLandscape) screenWidth else (screenWidth * 0.85f).toInt()
                app_ll_content.requestLayout()
            }
        }
        supportActionBar?.run {
            setDisplayShowTitleEnabled(true)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.app_ic_action_back_white)
        }
        if (!Settings.swipeHorizontal) {
            val lp = app_pdfview_bg.layoutParams
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            app_pdfview_bg.layoutParams = lp
        }
        isNightMode.value = Settings.nightMode
        nightModeBtn.isSelected = isNightMode.value == true
        nightModeBtn.background = if (nightModeBtn.isSelected) {
            resources.getDrawable(R.drawable.app_shape_circle_white_alpha)
        } else resources.getDrawable(R.drawable.app_shape_circle_black_alpha)
        // 移动到屏幕下方
        app_ll_read_method.translationY = ScreenUtils.getScreenHeight().toFloat()
        app_ll_more.translationY = ScreenUtils.getScreenHeight().toFloat()
        // 移动到屏幕左边
        app_ll_content.post {
            if (app_ll_content != null) {
                app_ll_content.translationX = -app_ll_content.measuredWidth.toFloat()
            }
        }
        if (Settings.swipeHorizontal) {
            app_tv_horizontal.setTextColor(resources.getColor(R.color.app_color_accent))
        } else {
            app_tv_vertical.setTextColor(resources.getColor(R.color.app_color_accent))
        }
        if (Settings.lockLandscape) {
            app_tv_lock_landscape.setTextColor(resources.getColor(R.color.app_color_accent))
            if (!ScreenUtils.isLandscape()) ScreenUtils.setLandscape(this)
        } else {
            app_tv_lock_landscape.setTextColor(Color.WHITE)
        }
        // 目录书签侧滑页初始化
        val fm = supportFragmentManager
        val adapter: FragmentPagerAdapter = PagerAdapter(fm)
        app_vp.adapter = adapter
        app_tab_layout.setupWithViewPager(app_vp)
        val tab1 = app_tab_layout.getTabAt(0)
        val tab2 = app_tab_layout.getTabAt(1)
        tab1?.setCustomView(R.layout.app_tab_content)
        tab2?.setCustomView(R.layout.app_tab_bookmark)
        getData(savedInstanceState)
        initScaleFactor()
        initListener()
        initPdf(uri, pdf)
    }

    /**
     * 解决锁定横屏时从后台回到前台时头两次点击无效
     */
    private fun fixBackToForegroundClick() {
        val screenHeight = ScreenUtils.getScreenHeight()
        app_ll_read_method.translationY = screenHeight.toFloat()
        app_ll_more.translationY = screenHeight.toFloat()
    }

    private fun checkScrollShortcut() {
        if (Settings.scrollShortCut && app_pdfview.curZoom.value != 1.0f) {
            scaleLevel.visibility = View.VISIBLE
        } else {
            scaleLevel.visibility = View.GONE
        }
    }

    private fun scaleViewParentAnim(alpha: Float, startDelayed: Long = 0L, listener: Animator.AnimatorListener? = null) {
        if (!Settings.scrollShortCut) {
            return
        }
        scaleLevel.animate()
                .setStartDelay(startDelayed)
                .setDuration(ANIM_DURATION)
                .alpha(alpha)
                .setListener(listener)
                .start()
    }

    private fun resetUIAndCancelAutoScroll() {
        // 书签页回原位
        app_ll_content.translationX = -app_ll_content.measuredWidth.toFloat()
        app_screen_cover.alpha = 0f // 隐藏界面遮罩
        // 阅读方式回原位
        app_ll_read_method.translationY = ScreenUtils.getScreenHeight().toFloat()
        app_ll_more.translationY = ScreenUtils.getScreenHeight().toFloat()
        if (autoDisp?.isDisposed == false) {
            autoDisp?.dispose()
            sbScrollLevel.visibility = View.GONE
        }
    }

    /**
     * 获取阅读进度的百分比
     */
    private fun getPercent(percent: Int, total: Int): String { // 创建一个数值格式化对象
        val numberFormat = NumberFormat.getInstance()
        // 设置精确到小数点后2位
        numberFormat.maximumFractionDigits = 1
        //计算x年x月的成功率
        val result = numberFormat.format(percent.toFloat() / total.toFloat() * 100f)
        return "$result%"
    }

    private fun initScaleFactor() {
        scaleFactor = pdf?.scaleFactor ?: 1.0f
        app_pdfview.apply {
            scaleX = scaleFactor
            scaleY = scaleFactor
        }
        app_sb_scale.progress = ((scaleFactor - 1) * 100).toInt()
    }

    private fun getData(savedInstanceState: Bundle?) {
        intent.apply {
            uri = data
            val uri = this@PreviewActivity.uri
            pdf = getParcelableExtra(EXTRA_PDF)
            // 如果都为空，那一般来自快捷方式，直接通过路径查找
            if (uri == null && pdf == null) {
                val path = getStringExtra(EXTRA_PATH)
                path?.let { pdf = DBHelper.queryPDFByPath(it) }
            } else if (uri != null) {
                val file = UriUtils.uri2File(uri)
                val path = if (file != null) UriUtils.uri2File(uri).absolutePath else null
                path?.also {
                    pdf = DBHelper.queryPDFByPath(it)
                    if (pdf == null) {
                        DBHelper.insert(listOf(it))
                        pdf = DBHelper.queryPDFByPath(it)
                    }
                }
            }
            pdf?.let {
                curPage = savedInstanceState?.getInt(BUNDLE_CUR_PAGE) ?: it.curPage
                password = savedInstanceState?.getString(BUNDLE_PASSWORD)
                pageCount = it.totalPage

                val cur = System.currentTimeMillis()
                @SuppressLint("SimpleDateFormat")
                val df: DateFormat = SimpleDateFormat("yyyyMMddHHmmss")
                it.latestRead = TimeUtils.millis2String(cur, df).toLong()
                DBHelper.updatePDF(it)
                DBHelper.insertRecent(it)
                DataManager.updatePDFs()
                EventBus.getDefault().post(RecentPDFEvent())
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initListener() {
        app_ll_bottombar.setOnClickListener { }
        app_ibtn_quickbar_action.setOnClickListener {
            // 当前页就是操作后的上一页或者下一页
            if (it.isSelected) {
                previousPage = app_pdfview.currentPage
                app_pdfview.jumpTo(nextPage) // Redo
            } else {
                nextPage = app_pdfview.currentPage
                app_pdfview.jumpTo(previousPage) // Undo
            }
            it.isSelected = !it.isSelected
        }
        app_tv_previous_chapter.setOnClickListener {
            // 减 1 是为了防止当前页面有标题的情况下无法跳转，因为是按标题来跳转
            if (hideBar) {
                return@setOnClickListener   // 防误触
            }
            var targetPage = app_pdfview.currentPage - 1
            if (pageList.isNotEmpty() && targetPage >= pageList[0]) {
                if (app_ll_undoredobar.visibility != View.VISIBLE) {
                    showQuickbar()
                }
                app_ibtn_quickbar_action.isSelected = false // 将状态调为 Undo
                while (!pageList.contains(targetPage.toLong())) {
                    if (targetPage < pageList[0]) {
                        return@setOnClickListener   // 如果实在匹配不到就跳出方法，不执行跳转
                    }
                    targetPage-- // 如果匹配不到会一直减 1 搜索
                }
                previousPage = app_pdfview.currentPage
                app_pdfview.jumpTo(targetPage)
            }
        }
        app_tv_next_chapter.setOnClickListener {
            if (hideBar) {
                return@setOnClickListener   // 防误触
            }
            // 这里的原理和上面跳转上一章节一样
            var targetPage = app_pdfview.currentPage + 1
            if (pageList.isNotEmpty() && targetPage <= pageList[pageList.size - 1]) {
                app_pdfview_bg.visibility = View.VISIBLE
                if (app_ll_undoredobar.visibility != View.VISIBLE) {
                    showQuickbar()
                }
                app_ibtn_quickbar_action.isSelected = false
                while (!pageList.contains(targetPage.toLong())) {
                    if (targetPage > pageList[pageList.size - 1]) {
                        return@setOnClickListener
                    }
                    targetPage++
                }
                previousPage = app_pdfview.currentPage
                app_pdfview.jumpTo(targetPage)
            }
        }
        app_tv_content.setOnClickListener(object : OnClickListenerImpl() {
            override fun onViewClick(v: View, interval: Long) {
                val tab = app_tab_layout.getTabAt(0)
                tab?.select()
                hideBar()
                enterFullScreen()
                showContent()
            }
        })
        app_tv_read_method.setOnClickListener(object : OnClickListenerImpl() {
            override fun onViewClick(v: View, interval: Long) {
                hideBar()
                enterFullScreen()
                showReadMethod()
            }
        })
        app_tv_auto_scroll.setOnClickListener {
            if (Settings.swipeHorizontal) {
                UiManager.showCenterShort(R.string.app_horizontal_does_not_support_auto_scroll)
                return@setOnClickListener
            }
            it.isSelected = !it.isSelected
            if (it.isSelected) {
                hideBar()
                showAutoScrollTipsDialog {
                    enterFullScreen()
                    sbScrollLevel.progress = Settings.scrollLevel.toInt() - 1
                    sbScrollLevel.visibility =
                            if (Settings.hideScrollLevelBar)
                                View.GONE
                            else
                                View.VISIBLE
                    autoDisp = startAutoScroll()
                }
            } else {
                if (autoDisp?.isDisposed == false) {
                    autoDisp?.dispose()
                }
                sbScrollLevel.visibility = View.GONE
            }
        }
        app_tv_bookmark.setOnClickListener {
            it.isSelected = !it.isSelected
            val curPage = app_pdfview.currentPage
            if (it.isSelected) {
                val title = getTitle(curPage)
                val time = System.currentTimeMillis()
                val bk = Bookmark(curPage, title, time)
                bookmarkMap[curPage.toLong()] = bk
            } else {
                bookmarkMap.remove(curPage.toLong())
            }
            bkFragInterface?.update(bookmarkMap.values)
            app_pdfview.invalidate()
            updateDB.value = 0
        }
        app_tv_bookmark.setOnLongClickListener {
            val tab = app_tab_layout.getTabAt(1)
            tab?.select()
            hideBar()
            enterFullScreen()
            showContent()
            true
        }
        app_tv_more.setOnClickListener(object : OnClickListenerImpl() {
            override fun onViewClick(v: View, interval: Long) {
                hideBar()
                enterFullScreen()
                showMore()
            }
        })
        app_tv_lock_landscape.setOnClickListener {
            if (ScreenUtils.isPortrait()) {
                Settings.lockLandscape = true
                ScreenUtils.setLandscape(this)
            } else {
                Settings.lockLandscape = false
                ScreenUtils.setPortrait(this)
            }
        }
        app_tv_export_image.setOnClickListener {
            hideMore()
            if (pdf != null) {
                launch {
                    withContext(Dispatchers.IO) {
                        val bmp = PdfUtils.pdfToBitmap(pdf!!.path, curPage)
                        val path = "${PathUtils.getExternalPicturesPath()}/YESPDF/${pdf!!.name}/第${curPage + 1}页.png"
                        AboutUtils.copyImageToDevice(this@PreviewActivity, bmp, path)
                    }
                    UiManager.showShort(R.string.app_export_image_succeed)
                }
            } else UiManager.showShort(R.string.app_not_support_external_open)
        }
        app_tv_clip.setOnClickListener(object : OnClickListenerImpl() {
            override fun onViewClick(v: View?, interval: Long) {
                hideMore()
                app_sb_scale.visibility = View.VISIBLE
            }
        })
        app_sb_scale.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                scaleFactor = 1 + progress / 100f
                app_pdfview.apply {
                    scaleX = scaleFactor
                    scaleY = scaleFactor
                }
                app_pdfview.invalidate()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                app_sb_scale.visibility = View.GONE
            }
        })
        app_tv_settings.setOnClickListener(object : OnClickListenerImpl() {
            override fun onViewClick(v: View, interval: Long) {
                hideBar()
                if (autoDisp?.isDisposed == false) {
                    autoDisp?.dispose()
                }
                start(this@PreviewActivity, REQUEST_CODE_SETTINGS)
            }
        })
        app_screen_cover.setOnTouchListener { _: View?, _: MotionEvent? ->
            if (app_ll_content.translationX == 0f) {
                hideContent(null)
                return@setOnTouchListener true
            }
            false
        }
        app_tv_horizontal.setOnClickListener {
            hideReadMethod()
            if (!Settings.swipeHorizontal) {
                if (autoDisp?.isDisposed == false) {
                    sbScrollLevel.visibility = View.GONE
                    autoDisp?.dispose()
                    UiManager.showCenterShort(R.string.app_horizontal_does_not_support_auto_scroll)
                    return@setOnClickListener
                }
                app_tv_horizontal.setTextColor(resources.getColor(R.color.app_color_accent))
                app_tv_vertical.setTextColor(resources.getColor(R.color.base_white))
                Settings.swipeHorizontal = true
                initPdf(uri, pdf)
            }
        }
        app_tv_vertical.setOnClickListener {
            hideReadMethod()
            if (Settings.swipeHorizontal) {
                app_tv_vertical.setTextColor(resources.getColor(R.color.app_color_accent))
                app_tv_horizontal.setTextColor(resources.getColor(R.color.base_white))
                Settings.swipeHorizontal = false
                initPdf(uri, pdf)
            }
        }
        nightModeBtn.setOnClickListener {
            it ?: return@setOnClickListener
            it.isSelected = !it.isSelected
            nightModeBtn.background = if (it.isSelected) {
                resources.getDrawable(R.drawable.app_shape_circle_white_alpha)
            } else resources.getDrawable(R.drawable.app_shape_circle_black_alpha)
            isNightMode.value = it.isSelected
        }
        sbScrollLevel.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {

            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isScrollLevelTouchFinish = false
                sbScrollLevel.alpha = 1.0f
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isScrollLevelTouchFinish = true
                sbScrollLevel.animate()
                        .alpha(0.4f)
                        .setDuration(ANIM_DURATION)
                        .setUpdateListener {
                            if (!isScrollLevelTouchFinish) {
                                sbScrollLevel.animate().cancel()
                                sbScrollLevel.alpha = 1.0f
                            }
                        }
                        .start()
                Settings.scrollLevel = (sbScrollLevel.progress + 1).toLong()
                autoDisp?.dispose()
                app_tv_auto_scroll.isSelected = true
                autoDisp = startAutoScroll()
            }
        })
        scale025.setOnClickListener(this)
        scale050.setOnClickListener(this)
        scale075.setOnClickListener(this)
        scale100.setOnClickListener(this)
        scale200.setOnClickListener(this)
        scale300.setOnClickListener(this)
    }

    private fun showAutoScrollTipsDialog(listener: (() -> Unit)?) {
        if (Settings.autoScrollTipsHasShown) {
            listener?.invoke()
            return
        }
        DialogManager.createAutoScrollTipsDialog(this) { arrow, seekBar, animatable, btn, dialog ->
            autoScrollDialog = dialog
            val arrowAnim = ValueAnimator.ofFloat(0f, 40f).apply {
                duration = 600L
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener { arrow.translationX = it.animatedValue as Float }
                start()
            }
            val seekBarAnim = ValueAnimator.ofInt(0, 100).apply {
                duration = 1800L
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener { seekBar.progress = it.animatedValue as Int }
                start()
            }
            animatable?.start()
            btn.setOnClickListener {
                arrowAnim.cancel()
                seekBarAnim.cancel()
                animatable?.stop()
                dialog.dismiss()
                listener?.invoke()
            }
            dialog.setOnDismissListener {
                Settings.autoScrollTipsHasShown = true
                arrowAnim.cancel()
                seekBarAnim.cancel()
                animatable?.stop()
            }
            dialog.show()
        }
    }

    private fun startAutoScroll(): Disposable {
        return Observable.interval(Settings.scrollLevel, TimeUnit.MILLISECONDS)
                .doOnDispose {
                    app_tv_auto_scroll.isSelected = false
                    isPause = false
                }
                .doOnSubscribe {
                    app_tv_auto_scroll.isSelected = true
                }
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .`as`(AutoDispose.autoDisposable(AndroidLifecycleScopeProvider.from(this@PreviewActivity)))
                .subscribe {
                    if (!app_pdfview.isRecycled && !isPause) {
                        app_pdfview.moveRelativeTo(0f, OFFSET_Y)
                        app_pdfview.loadPageByOffset()
                    }
                }
    }

    private fun showReadMethod() {
        barShowState.value = true
        val screenHeight = ScreenUtils.getScreenHeight()
        var viewHeight = app_ll_read_method.measuredHeight
        if (app_ll_read_method.translationY < viewHeight) {
            viewHeight += ConvertUtils.dp2px(24f)
        }
        app_ll_read_method.animate()
                .setDuration(ANIM_DURATION)
                .setStartDelay(100)
                .translationY(screenHeight - viewHeight.toFloat())
                .start()
    }

    private fun hideReadMethod() {
        barShowState.value = false
        val screenHeight = ScreenUtils.getScreenHeight()
        app_ll_read_method.animate()
                .setDuration(ANIM_DURATION)
                .translationY(screenHeight.toFloat())
                .start()
    }

    private fun showMore() {
        barShowState.value = true
        val screenHeight = ScreenUtils.getScreenHeight()
        var viewHeight = app_ll_more.measuredHeight
        if (app_ll_more.translationY < viewHeight) {
            viewHeight += ConvertUtils.dp2px(24f)
        }
        app_ll_more.animate()
                .setDuration(ANIM_DURATION)
                .setStartDelay(100)
                .translationY(screenHeight - viewHeight.toFloat())
                .start()
    }

    private fun hideMore() {
        barShowState.value = false
        val screenHeight = ScreenUtils.getScreenHeight()
        app_ll_more.animate()
                .setDuration(ANIM_DURATION)
                .translationY(screenHeight.toFloat())
                .start()
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun initPdf(uri: Uri?, pdf: PDF?) {
        app_sb_progress.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, i: Int, b: Boolean) {
                app_tv_pageinfo.text = (i + 1).toString() + " / " + pageCount
                // Quickbar
                app_quickbar_title.text = getTitle(i)
                app_tv_pageinfo2.text = (i + 1).toString() + " / " + pageCount
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                app_ibtn_quickbar_action.isSelected = false
                previousPage = seekBar.progress
                if (!hideBar) {
                    showQuickbar()
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                nextPage = seekBar.progress
                app_pdfview.jumpTo(seekBar.progress)
            }
        })
        var configurator: Configurator
        when {
//            uri != null -> {
//                val file = UriUtils.uri2File(uri)
//                val path = if (file != null) UriUtils.uri2File(uri).absolutePath else null
//                app_tv_pageinfo.text = "1 / $pageCount"
//                val bookName = path?.substring(path.lastIndexOf("/") + 1, path.length - 4) ?: ""
//                toolbar?.post { toolbar?.title = bookName }
//                configurator = app_pdfview.fromUri(uri).defaultPage(curPage)
//            }
            pdf != null -> {
                val bkJson = pdf.bookmark // 获取书签 json
                val bkList = GsonUtils.fromJson<List<Bookmark>>(bkJson, object : TypeToken<List<Bookmark?>?>() {}.type)
                if (bkList != null) {
                    for (bk in bkList) {
                        LogUtils.e(bk)
                        val pageId = bk.pageId
                        bookmarkMap[pageId.toLong()] = bk
                    }
                }
                app_sb_progress.progress = curPage
                app_tv_pageinfo.text = (curPage + 1).toString() + " / " + pageCount
                toolbar?.post { toolbar?.title = pdf.name }
                configurator = app_pdfview.fromFile(File(pdf.path)).defaultPage(curPage)
            }
            else -> {
                UiManager.showCenterShort(R.string.app_file_is_empty)
                finish()
                return
            }
        }
        if (password != null) {
            configurator = configurator.password(password)
        }
        configurator.disableLongpress()
                .swipeHorizontal(Settings.swipeHorizontal)
                .nightMode(isNightMode.value == true)
                .pageFling(Settings.swipeHorizontal)
                .pageSnap(Settings.swipeHorizontal)
                .enableDoubletap(false)
                .fitEachPage(true) // .spacing(ConvertUtils.dp2px(4))
                .onError { throwable: Throwable ->
                    LogUtils.e(throwable.message)
                    showError(throwable)
                }
                .onPageError { page: Int, throwable: Throwable ->
                    LogUtils.e(throwable.message)
                    UiManager.showShort(getString(R.string.app_cur_page_parse_error, page))
                }
                .onDrawAll { canvas: Canvas?, pageWidth: Float, _: Float, displayedPage: Int ->
                    this.canvas = canvas
                    this.pageWidth = pageWidth
                    if (bookmarkMap.containsKey(displayedPage.toLong())) {
                        drawBookmark(canvas, pageWidth)
                    }
                }
                .onPageChange { page: Int, _: Int ->
                    curPage = page
                    val pageInfo = "${page + 1} / ${this.pageCount}"
                    app_tv_bookmark.isSelected = bookmarkMap.containsKey(page.toLong()) // 如果是书签则标红
                    app_quickbar_title.text = getTitle(page)
                    app_tv_pageinfo2.text = pageInfo
                    app_tv_pageinfo.text = pageInfo
                    app_sb_progress.progress = page
                }
                .onLoad {
                    initContentAndBookmark()
                    initAboutPage()
                    initBgSize()
                    init = false
                }
                .onTap { event: MotionEvent ->
                    if (judgeCloseMore()) return@onTap true
                    if (judgeCloseReadMethod()) return@onTap true
                    if (judgeAutoScrollPause()) return@onTap true
                    judgeFlipPage(event)
                }
                .load()
    }

    private fun initBgSize() {
        if (isNightMode.value != true) {
            if (Settings.swipeHorizontal) {
                val page = (app_pdfview.pageCount / 2.toFloat()).roundToInt()
                val sizeF = app_pdfview.getPageSize(page)
                val lp = app_pdfview_bg.layoutParams
                lp.width = sizeF.width.toInt()
                lp.height = sizeF.height.toInt()
                app_pdfview_bg.layoutParams = lp
            } else {
                val lp = app_pdfview_bg.layoutParams
                lp.width = ScreenUtils.getScreenWidth()
                lp.height = ScreenUtils.getScreenHeight()
                app_pdfview_bg.layoutParams = lp
            }
        }
    }

    private fun initAboutPage() {
        pageCount = app_pdfview.pageCount
        app_sb_progress.max = pageCount - 1
        val keySet: Set<Long> = contentMap.keys
        pageList.addAll(keySet)
        pageList.sort()
    }

    private fun initContentAndBookmark() {
        val list = app_pdfview.tableOfContents
        if (init) {
            findContent(list)
        }
        val fragmentList = supportFragmentManager.fragments
        for (f in fragmentList) {
            if (f is IContentFragInterface) {
                contentFragInterface = f
            } else if (f is IBkFragInterface) {
                bkFragInterface = f
            }
        }
        if (init) {
            contentFragInterface?.update(list)
        }
        bkFragInterface?.update(bookmarkMap.values)
    }

    private fun showError(throwable: Throwable) {
        if (throwable is PdfPasswordException) {
            if (!StringUtils.isEmpty(password)) {
                UiManager.showCenterShort(R.string.app_password_error)
            }
            showInputDialog()
        } else {
            showAlertDialog()
        }
    }

    private fun judgeFlipPage(event: MotionEvent): Boolean {
        val x = event.rawX
        val previous: Float
        val next: Float
        if (Settings.clickFlipPage) {
            previous = ScreenUtils.getScreenWidth() * 0.3f
            next = ScreenUtils.getScreenWidth() * 0.7f
        } else {
            previous = 0f
            next = ScreenUtils.getScreenWidth().toFloat()
        }
        if (ScreenUtils.isPortrait() && x <= previous) {
            if (toolbar?.alpha == 1.0f) {
                hideBar()
                enterFullScreen()
            } else {
                var currentPage = app_pdfview.currentPage
                app_pdfview.jumpTo(--currentPage, true)
            }
        } else if (ScreenUtils.isPortrait() && x >= next) {
            if (toolbar?.alpha == 1.0f) {
                hideBar()
                enterFullScreen()
            } else {
                var currentPage = app_pdfview.currentPage
                app_pdfview.jumpTo(++currentPage, true)
            }
        } else {
            val visible = (toolbar?.alpha == 1.0f
                    && app_ll_bottombar.alpha == 1.0f)
            if (visible) {
                hideBar()
                enterFullScreen()
            } else {
                if (app_sb_scale.visibility == View.VISIBLE) {
                    app_sb_scale.visibility = View.GONE
                } else {
                    exitFullScreen()
                    showBar()
                }
            }
        }
        return true
    }

    private fun judgeCloseMore(): Boolean {
        if (app_ll_more.translationY != ScreenUtils.getScreenHeight().toFloat()) {
            hideMore()
            return true
        }
        return false
    }

    private fun judgeCloseReadMethod(): Boolean {
        if (app_ll_read_method.translationY != ScreenUtils.getScreenHeight().toFloat()) {
            hideReadMethod()
            return true
        }
        return false
    }

    private fun judgeAutoScrollPause(): Boolean {
        if (autoDisp?.isDisposed == false) {
            isPause = if (toolbar?.alpha == 1.0f) {
                hideBar()
                enterFullScreen()
                false
            } else {
                !isPause
            }
            return true
        }
        return false
    }

    private fun showInputDialog() {
        inputDialog.show()
    }

    private fun showAlertDialog() {
        alertDialog.show()
    }

    private fun drawBookmark(canvas: Canvas?, pageWidth: Float) {
        val bitmap = BitmapFactory.decodeResource(resources, R.drawable.app_img_bookmark)
        val curPageWidth = pageWidth * scaleFactor
        val margin = (curPageWidth - pageWidth) / 3f
        val left = pageWidth - ConvertUtils.dp2px(36f) - margin
        canvas?.drawBitmap(bitmap, left, 0f, paint)
    }

    private fun showQuickbar() {
        app_ll_undoredobar.animate()
                .setDuration(50)
                .alpha(1f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) {
                        app_ll_undoredobar.visibility = View.VISIBLE
                    }
                })
                .start()
    }

    private fun showContent() {
        barShowState.value = true
        app_ll_content.animate()
                .setDuration(ANIM_DURATION)
                .setStartDelay(100)
                .translationX(0f)
                .setUpdateListener { valueAnimator: ValueAnimator -> app_screen_cover.alpha = valueAnimator.animatedFraction }
                .start()
    }

    private fun hideContent(listener: Animator.AnimatorListener?) {
        barShowState.value = false
        app_ll_content.animate()
                .setDuration(ANIM_DURATION)
                .translationX(-app_ll_content.measuredWidth.toFloat())
                .setUpdateListener { valueAnimator: ValueAnimator -> app_screen_cover.alpha = 1 - valueAnimator.animatedFraction }
                .setListener(listener)
                .start()
    }

    private fun findContent(list: List<PdfDocument.Bookmark>) {
        for (bk in list) {
            contentMap[bk.pageIdx] = bk
            if (bk.hasChildren()) {
                findContent(bk.children)
            }
        }
    }

    private fun getTitle(page: Int): String? {
        if (pageList.isEmpty()) {
            return null
        }
        var tempPage = page
        if (contentMap.isEmpty()) return getString(R.string.app_have_no_content)
        var title: String?
        val bk = contentMap[tempPage.toLong()]
        title = bk?.title
        if (StringUtils.isEmpty(title)) {
            if (tempPage < pageList[0]) {
                title = contentMap[pageList[0]]?.title
            } else {
                var index = pageList.indexOf(tempPage.toLong())
                while (index == -1) {
                    index = pageList.indexOf(tempPage--.toLong())
                }
                title = contentMap[pageList[index]]?.title
            }
        }
        return title
    }

    private fun showBar() {
        hideBar = false
        barShowState.value = true
        toolbar?.animate()?.setDuration(ANIM_DURATION)?.alpha(1f)
                ?.setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) {
                        toolbar?.visibility = View.VISIBLE
                    }
                })?.start()
        app_ll_bottombar.animate().setDuration(ANIM_DURATION).alpha(1f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) {
                        app_ll_bottombar.visibility = View.VISIBLE
                    }
                }).start()
        app_tv_pageinfo.animate().setDuration(ANIM_DURATION).alpha(1f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) {
                        app_tv_pageinfo.visibility = View.VISIBLE
                    }
                }).start()
        nightModeBtn.animate().setDuration(ANIM_DURATION).alpha(1f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) {
                        nightModeBtn.visibility = View.VISIBLE
                    }
                }).start()
    }

    private fun hideBar() {
        hideBar = true
        barShowState.value = false
        toolbar?.animate()?.setDuration(ANIM_DURATION)?.alpha(0f)
                ?.setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        toolbar?.visibility = View.GONE
                    }
                })?.start()
        app_ll_bottombar.animate().setDuration(ANIM_DURATION).alpha(0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (app_ll_bottombar != null) app_ll_bottombar.visibility = View.GONE
                    }
                }).start()
        app_tv_pageinfo.animate().setDuration(ANIM_DURATION).alpha(0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (app_tv_pageinfo != null) app_tv_pageinfo.visibility = View.GONE
                    }
                }).start()
        app_ll_undoredobar.animate().setDuration(ANIM_DURATION).alpha(0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (app_ll_undoredobar != null) app_ll_undoredobar.visibility = View.GONE
                    }
                }).start()
        nightModeBtn.animate().setDuration(ANIM_DURATION).alpha(0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        nightModeBtn.visibility = View.GONE
                    }
                }).start()
        if (app_ibtn_quickbar_action != null) app_ibtn_quickbar_action.isSelected = false // 初始化为 Undo 状态
    }

    private fun enterFullScreen() {
        UiManager.setTransparentNavigationBar(window)
        if (Settings.showStatusBar) {
            UiManager.setTranslucentStatusBar(this)
            app_ll_content.setPadding(0, ConvertUtils.dp2px(25f), 0, 0)
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        } else {
            UiManager.setTransparentStatusBar(this)
            app_ll_content.setPadding(0, 0, 0, 0)
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    private fun exitFullScreen() {
        if (Settings.showStatusBar) {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
//        toolbar?.post { UiManager.setNavigationBarColor(this, resources.getColor(R.color.base_black)) }
    }
    //endregion

    companion object {
        const val EXTRA_PATH = "EXTRA_ID"
        private const val EXTRA_PDF = "EXTRA_PDF"
        private const val BUNDLE_CUR_PAGE = "BUNDLE_CUR_PAGE"
        private const val BUNDLE_PASSWORD = "BUNDLE_PASSWORD"
        private const val REQUEST_CODE_SETTINGS = 101
        private const val OFFSET_Y = -0.5f // 自动滚动的偏离值
        private const val ANIM_DURATION = 250L
        private const val SCALE_VIEW_ITEM_ANIM_DURATION = 150L
        private val SCALE_VIEW_ITEM_TRANS_VALUE = ConvertUtils.dp2px(12f).toFloat()
        /**
         * 非外部文件打开
         */
        fun start(context: Context, pdf: PDF?) {
            val starter = Intent(context, PreviewActivity::class.java)
            starter.putExtra(EXTRA_PDF, pdf)
            context.startActivity(starter)
        }
    }
}