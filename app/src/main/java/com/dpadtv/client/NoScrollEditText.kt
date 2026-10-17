package com.dpadtv.client

import android.content.Context
import android.util.AttributeSet
import android.widget.EditText

/**
 * 解决「输入框开头字符被裁掉」的确定性做法。
 *
 * 现象：URL/密码这类长文本，显示成 `ttp://…`（第一个字符不见）。
 *
 * 根因：EditText（TextView）在**首次布局前宽度为 0**，此时 setText 会让它按
 * 「文字比可视区宽」算出一个横向滚动偏移 scrollX；等布局把宽度撑开后，这个偏移
 * 不会被清除 —— 框架之后还会在布局期间用 `bringPointIntoView` 把光标滚到可见处，
 * 于是无论用 post{} 还是 setSelection 纠正，都会被下一次布局覆盖。
 *
 * 做法：每次布局完成后自己核对一次——如果文字实际放得下，就强制把 scrollX 归零。
 * 放不下（真的超长）时保留滚动，这样光标仍然可见，编辑体验不变。
 */
class NoScrollEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : EditText(context, attrs) {

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val textLayout = layout ?: return
        val visible = width - paddingLeft - paddingRight
        // 文字宽度没超过可视区，就不该有横向偏移
        if (visible > 0 && textLayout.width <= visible && scrollX != 0) {
            scrollTo(0, 0)
        }
    }
}
