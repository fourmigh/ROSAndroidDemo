package org.caojun.demo.rosandroid.utils

import android.app.ActivityManager
import android.content.Context
import android.content.Context.ACTIVITY_SERVICE
import android.content.pm.PackageManager


/**
 * 应用帮助类
 */
object AppHelper {

    /**
     * 判断本地是否已经安装好了指定的应用程序包
     *
     * @param packageNameTarget ：待判断的 App 包名，如 微博 com.sina.weibo
     * @return 已安装时返回 true,不存在时返回 false
     */
    fun isAppExist(context: Context, packageNameTarget: String): Boolean {
        if ("" != packageNameTarget.trim { it <= ' ' }) {
            val packageManager = context.packageManager
            val packageInfoList = packageManager.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            for (packageInfo in packageInfoList) {
                val packageNameSource = packageInfo.packageName
                if (packageNameSource == packageNameTarget) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * 将本应用置顶到最前端
     * 当本应用位于后台时，则将它切换到最前端
     *
     * @param context
     * @return true：成功从后台切换到前台或已在前台
     * @return false：应用已关闭
     */
    fun setTopApp(context: Context): Boolean {
        if (!isRunningForeground(context)) {
            /**获取ActivityManager */
            val activityManager = context.getSystemService(ACTIVITY_SERVICE) as ActivityManager

            /**获得当前运行的task(任务) */
            val taskInfoList = activityManager.getRunningTasks(100)
            for (taskInfo in taskInfoList) {
                /**找到本应用的 task，并将它切换到前台 */
                if (taskInfo.topActivity.packageName == context.packageName) {
                    activityManager.moveTaskToFront(taskInfo.id, 0)
                    return true
                }
            }
            return false
        }
        return true
    }

    /**
     * 判断本应用是否已经位于最前端
     *
     * @param context
     * @return 本应用已经位于最前端时，返回 true；否则返回 false
     */
    fun isRunningForeground(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val appProcessInfoList = activityManager.runningAppProcesses
        /**枚举进程 */
        for (appProcessInfo in appProcessInfoList) {
            if (appProcessInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                if (appProcessInfo.processName == context.applicationInfo.processName) {
                    return true
                }
            }
        }
        return false
    }
}