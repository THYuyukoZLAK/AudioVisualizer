package top.aerohaku.androidapp

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import top.aerohaku.androidapp.ui.visualizer.VisualizerScreen

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Visualizer)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        // 只有可视化这一个目的地。
        //
        // 设置页改成了**浮在可视化界面之上的浮层**（见 VisualizerScreen 里的
        // showSettings），而不是另一个导航目的地 —— 因为要做「拖动滑块时
        // 让设置页变透明、实时看到背后的调整效果」。目的地切换会把可视化
        // 整棵子树拆掉，那样就什么都看不到了。
        entry<Visualizer> {
          VisualizerScreen(modifier = Modifier.fillMaxSize())
        }
      },
  )
}
