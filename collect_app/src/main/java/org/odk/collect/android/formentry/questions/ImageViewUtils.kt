package org.odk.collect.android.formentry.questions

import android.widget.ImageView

object ImageViewUtils {

    /**
     * Clears the size a recycled view's [ImageView] still has from the previous choice and then
     * asks for a fresh layout pass.
     *
     * Zeroing the frame stops the previous image from being shown at its old size while the new
     * one loads. On its own that is not enough though: laying a view out also marks it as laid
     * out, so a recycled view can then never be measured again and the new image stays 0x0,
     * which is what made images look like they randomly disappeared while scrolling.
     */
    @JvmStatic
    fun resetSizeForNewImage(imageView: ImageView) {
        imageView.layout(0, 0, 0, 0)
        imageView.requestLayout()
    }
}
