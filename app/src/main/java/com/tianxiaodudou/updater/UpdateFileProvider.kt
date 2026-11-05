package com.tianxiaodudou.updater

import androidx.core.content.FileProvider

/** 只是为了给安装 APK 暴露一个 content:// URI，authority 由宿主 applicationId 决定。 */
class UpdateFileProvider : FileProvider()
