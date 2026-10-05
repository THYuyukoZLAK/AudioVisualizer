package top.aerohaku.androidapp.theme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 正在被拖动的滑杆信息。
 *
 * @param cardId 所属卡片（只用于排查，UI 不再靠它区分亮度）
 * @param label 这一项的标签，例如「背景暗化」；没传就是 null
 */
data class SliderDragInfo(
  val cardId: String,
  val label: String?,
  val value: Float,
  val range: ClosedFloatingPointRange<Float>,
)

/**
 * 「正在拖哪根滑杆、拖到多少」的全局状态。
 *
 * ## 目的
 *
 * 调版式必须边调边看：按住滑杆时设置页整体变透明，露出背后的可视化界面。
 *
 * ## ⚠️ 为什么是「整体透明 + 浮层 HUD」而不是「只保留被拖的那一根」
 *
 * 最直觉的做法是「整页淡下去、被拖的那根保持不透明」，但**做不到** ——
 * `alpha` 沿父链**相乘**：父级 0.1 的话子节点最多也就 0.1，永远亮不回来。
 * 想突破就只能把那一行**挪到被淡化的子树之外**（重绘副本 / `movableContent`），
 * 要么坐标会漂、要么会打断正在进行的拖动手势，得不偿失。
 *
 * 所以折中成：[SettingsDrag] 把「标签 + 当前值」交给一个**独立的浮层**
 * （见 `ui/settings/SliderHud.kt`）全不透明地显示。正在拖的那根虽然淡了，
 * 但手指本来就在上面，而**这一项是什么、拖到多少**始终看得清清楚楚。
 *
 * ## 卡片身份怎么传
 *
 * [ScexCard] 经 `CompositionLocal` 把 id 下发，[ScexSlider] 读出来上报 ——
 * 不必在每个调用点手写键。
 */
object SettingsDrag {

  private val _active = MutableStateFlow<SliderDragInfo?>(null)

  /** 当前正在被拖动的滑杆；`null` 表示没有在拖 */
  val active: StateFlow<SliderDragInfo?> = _active.asStateFlow()

  /**
   * 按下或开始拖动时调用。
   *
   * ⚠️ **按下就要调**，不能等数值变了再调 —— 那样「按住不动」会毫无反应。
   */
  fun begin(
    cardId: String,
    label: String?,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
  ) {
    _active.value = SliderDragInfo(cardId, label, value, range)
  }

  /** 拖动过程中数值变化时调用 */
  fun updateValue(value: Float) {
    _active.value = _active.value?.copy(value = value)
  }

  fun end() {
    _active.value = null
  }
}
