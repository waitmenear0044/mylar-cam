package com.vhscam.app

/** Имя и версия приложения (задаются в version.properties). */
object AppInfo {
    const val NAME = BuildConfig.APP_NAME

    /** «Mylar Cam 0.7.1 (701)». */
    const val LABEL = "${BuildConfig.APP_NAME} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
}
