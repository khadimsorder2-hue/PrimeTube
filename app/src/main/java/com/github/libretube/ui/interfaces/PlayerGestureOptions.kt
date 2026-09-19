package com.github.libretube.ui.interfaces

interface PlayerGestureOptions {

    fun onSingleTap(areControlsLocked: Boolean)

    fun onDoubleTapCenterScreen()

    fun onDoubleTapLeftScreen()

    fun onDoubleTapRightScreen()

    fun onSwipeLeftScreen(distanceY: Float, positionY: Float)

    fun onSwipeRightScreen(distanceY: Float, positionY: Float)

    fun onSwipeCenterScreen(distanceY: Float, positionY: Float)

    fun onSwipeEnd()

    /**
     * PrimeTube: horizontal swipe on the fullscreen player surface.
     *
     * @param distanceFraction the total horizontal distance from the gesture start,
     * relative to the view width. Positive values mean the finger is dragged to the
     * left (= next video), negative values to the right (= previous video).
     */
    fun onSwipeHorizontalScreen(distanceFraction: Float)

    fun onZoom()

    fun onMinimize()

    fun onFullscreenChange(isFullscreen: Boolean)

    fun onLongPress()

    fun onLongPressEnd()

    /**
     *  Returns a pair of the width and height of the view this listener is used for
     *  These measures change when the screen orientation changes or fullscreen is entered, thus
     *  needs to be refreshed manually all the time when needed.
     */
    fun getViewMeasures(): Pair<Int, Int>
}
