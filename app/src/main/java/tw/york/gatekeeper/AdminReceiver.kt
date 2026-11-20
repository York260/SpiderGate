package tw.york.gatekeeper

import android.app.admin.DeviceAdminReceiver

/** 裝置管理員：啟用後，要先到設定取消才能解除安裝守門員。 */
class AdminReceiver : DeviceAdminReceiver()
