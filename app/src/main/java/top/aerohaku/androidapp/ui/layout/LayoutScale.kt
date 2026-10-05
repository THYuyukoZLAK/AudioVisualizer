package top.aerohaku.androidapp.ui.layout

import kotlin.math.min

/**
 * 版式自动缩放：把「设计画布」等比铺进实际可用区域。
 *
 * ## 要解决的问题
 *
 * 所有部件的位置与尺寸都存在 [WidgetConfig] 里，单位是 **dp**，
 * 而且是照着开发机（联想 TB321FU，2560×1600 @340dpi，去掉系统栏后 ≈ 1204×710dp）调的。
 * 换到小屏设备上这些绝对值不会收缩 ——
 * 频谱是「左下 + (64, −124)dp，900×220dp」，在 800dp 宽的屏幕上会直接冲出右边界；
 * 歌词是「右上 −64dp，560dp 宽」，会和曲目信息叠在一起。
 *
 * ## 做法：覆盖 `LocalDensity`，而不是去乘每个 dp 值
 *
 * 部件的 dp 值散落在十几个组件里（含字号、边框、内边距），逐个乘系数既容易漏、又会把组件搞脏。
 * 而 Compose 的 `LocalDensity` 恰好就是「1dp 等于多少 px」——
 * 把它换成缩放后的密度，**所有** dp/sp 都会一起等比缩放，组件代码一行都不用改。
 *
 * 推导：设想让 `p` 个设计 dp 落到实际 `p × s` dp 上，即 `p × s × naturalDensity` px。
 * 覆盖后的密度 `d` 需满足 `p × d = p × s × naturalDensity`，即 `d = s × naturalDensity`。
 * 而 `s = min(可用宽px / REF宽, 可用高px / REF高) / naturalDensity`，代入得：
 *
 * ```
 * d = min(可用宽px / REFERENCE_WIDTH_DP, 可用高px / REFERENCE_HEIGHT_DP)
 * ```
 *
 * 也就是**密度直接等于「每个设计 dp 分到多少 px」**，与设备自身的密度无关。
 *
 * ## 两个刻意的限制
 *
 * 1. **只缩不放**：结果再与设备自然密度取 min。比设计画布大的屏幕就按原设计渲染，
 *    不会把字放大成巨无霸 —— 也顺带保证开发机上的观感**完全不变**。
 * 2. **取宽、高两个方向的较小值**，所以是等比缩放，不会把圆拉成椭圆、把字压扁。
 *
 * ⚠️ 缩放只作用于**部件层**。右下角设置按钮和底部提示条留在自然密度下 ——
 * 纯 UI 控件被缩到 0.5 倍会小到点不中、看不清。
 */
object LayoutScale {

  /**
   * 设计画布尺寸（dp）= 开发机去掉系统栏后的实际可用区域。
   *
   * `2560px / 2.125 = 1204dp`；`(1600 − 57 − 33)px / 2.125 = 710dp`。
   * 这两个数只影响「多小的屏幕开始缩」，改大改小不会动开发机上的观感。
   */
  const val REFERENCE_WIDTH_DP = 1204f
  const val REFERENCE_HEIGHT_DP = 710f

  /**
   * 算出渲染版式该用的密度。
   *
   * @param availableWidthPx 可用宽（px）。**不含系统栏、不含提示条预留**——
   *   提示条出现时预留高度会让可用区变矮，若把它算进来，提示条一出一收整版就会跳一下。
   * @param availableHeightPx 可用高（px），同上
   * @param naturalDensity 设备自然密度（`LocalDensity.current.density`）
   */
  fun densityFor(availableWidthPx: Int, availableHeightPx: Int, naturalDensity: Float): Float {
    if (availableWidthPx <= 0 || availableHeightPx <= 0 || naturalDensity <= 0f) return naturalDensity
    val fit = min(
      availableWidthPx / REFERENCE_WIDTH_DP,
      availableHeightPx / REFERENCE_HEIGHT_DP,
    )
    return min(fit, naturalDensity)
  }

  /** 相对设计尺寸的缩放倍率，供 UI 展示与排查用 */
  fun scaleFor(availableWidthPx: Int, availableHeightPx: Int, naturalDensity: Float): Float =
    if (naturalDensity <= 0f) 1f
    else densityFor(availableWidthPx, availableHeightPx, naturalDensity) / naturalDensity
}
