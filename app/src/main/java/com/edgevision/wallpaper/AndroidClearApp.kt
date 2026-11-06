package com.edgevision.wallpaper

import android.app.Application

/**
 * Python is intentionally started lazily: the settings screen never needs it, and
 * starting the interpreter only when the wallpaper becomes visible keeps launch
 * time and idle memory low.
 */
class AndroidClearApp : Application()
