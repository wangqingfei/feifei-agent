package com.mini.agent

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class SettingsActivity : Activity() {

    private lateinit var etBase: EditText
    private lateinit var etKey: EditText
    private lateinit var etCity: EditText
    private lateinit var etMailUser: EditText
    private lateinit var etMailPass: EditText
    private lateinit var etMailImap: EditText
    private lateinit var etMailSmtp: EditText
    private lateinit var cbMailSend: CheckBox
    private lateinit var etNetdiskToken: EditText
    private lateinit var permList: LinearLayout
    private lateinit var etAmapKey: EditText
    private lateinit var etAmapSecurity: EditText
    private lateinit var etAmapWebKey: EditText
    private lateinit var cbDirectCall: CheckBox
    private lateinit var cbSendSms: CheckBox
    private lateinit var cbNotifEnabled: CheckBox
    private lateinit var tvModel: TextView
    private lateinit var tvProvider: TextView
    private lateinit var tvManageTasks: TextView

    private var currentModel = AgentFactory.DEFAULT_MODEL

    /** 已实测能通对话 + 能调工具的模型 */
    /** 可选模型来自 config.json（模型自己也能读到同一份清单） */
    private val models: List<Pair<String, String>> by lazy { AgentConfig.models(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etBase = findViewById(R.id.etBase)
        etKey = findViewById(R.id.etKey)
        etCity = findViewById(R.id.etCity)
        etMailUser = findViewById(R.id.etMailUser)
        etMailPass = findViewById(R.id.etMailPass)
        etMailImap = findViewById(R.id.etMailImap)
        etMailSmtp = findViewById(R.id.etMailSmtp)
        cbMailSend = findViewById(R.id.cbMailSend)
        etNetdiskToken = findViewById(R.id.etNetdiskToken)
        etAmapKey = findViewById(R.id.etAmapKey)
        etAmapSecurity = findViewById(R.id.etAmapSecurity)
        etAmapWebKey = findViewById(R.id.etAmapWebKey)
        permList = findViewById(R.id.permList)
        cbDirectCall = findViewById(R.id.cbDirectCall)
        cbSendSms = findViewById(R.id.cbSendSms)
        cbNotifEnabled = findViewById(R.id.cbNotifEnabled)
        renderPerms()
        refreshNotifAccess()
        tvModel = findViewById(R.id.tvModel)
        tvManageTasks = findViewById(R.id.tvManageTasks)
        findViewById<Button>(R.id.btnNetdiskTest).setOnClickListener { testNetdisk() }
        tvManageTasks.setOnClickListener {
            startActivity(android.content.Intent(this, TasksActivity::class.java))
        }

        val p = AgentFactory.prefs(this)
        val api = AgentConfig.active(this)
        etBase.setText(api.baseUrl.ifEmpty { AgentFactory.DEFAULT_BASE })
        etKey.setText(api.apiKey)
        etCity.setText(p.getString("cityManual", ""))
        etNetdiskToken.setText(p.getString("netdiskToken", ""))
        etAmapKey.setText(p.getString("amapKey", ""))
        etAmapSecurity.setText(p.getString("amapSecurity", ""))
        etAmapWebKey.setText(p.getString("amapWebKey", ""))
        cbDirectCall.isChecked = p.getBoolean("allowDirectCall", false)
        cbSendSms.isChecked = p.getBoolean("allowSendSms", false)
        cbNotifEnabled.isChecked = p.getBoolean("notifEnabled", true)
        etMailUser.setText(p.getString("mailUser", ""))
        etMailPass.setText(p.getString("mailPass", ""))
        etMailImap.setText(p.getString("mailImap", ""))
        etMailSmtp.setText(p.getString("mailSmtp", ""))
        cbMailSend.isChecked = p.getBoolean("mailAllowSend", false)

        // 老版本那一条写死的简报，第一次进来时搬成任务
        TaskStore.migrateLegacy(this)
        val taskCount = TaskStore.load(this).count { it.enabled }
        tvManageTasks.text = "管理定时任务（已开启 " + taskCount + " 个）›"

        currentModel = api.model.ifEmpty { AgentFactory.DEFAULT_MODEL }
        tvModel.text = currentModel

        tvProvider = findViewById(R.id.tvProvider)
        tvProvider.text = "当前接口：" + api.name + "（" + api.id + "）· 点一下切换"
        tvProvider.setOnClickListener { pickProvider() }

        tvModel.setOnClickListener { pickModel() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { test() }
        findViewById<Button>(R.id.btnMailTest).setOnClickListener { testMail() }
        findViewById<Button>(R.id.btnViewLog).setOnClickListener { viewLog() }
        val btnCrash = findViewById<Button>(R.id.btnViewCrash)
        btnCrash.setOnClickListener { viewCrashLog() }
        refreshCrashBtn(btnCrash)
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
    }

    private fun viewLog() {
        val log = RunLog.tail(this, 60, false)
        AlertDialog.Builder(this)
            .setTitle("运行日志（最近 60 条）")
            .setMessage(log)
            .setPositiveButton("关闭", null)
            .setNeutralButton("清空") { _, _ ->
                RunLog.clear(this)
                toast("已清空日志")
            }
            .show()
    }

    /**
     * 崩溃日志：App 挂掉时的现场。
     * 手机上看不到 logcat，所以必须自己存——不然崩溃就是彻底没线索。
     */
    private fun viewCrashLog() {        val n = CrashLog.count(this)
        val log = CrashLog.tail(this, 5)
        AlertDialog.Builder(this)
            .setTitle(if (n > 0) "崩溃日志（$n 条，显示最近 5 条）" else "崩溃日志")
            .setMessage(log)
            .setPositiveButton("关闭", null)
            .setNeutralButton("清空") { _, _ ->
                CrashLog.clear(this)
                refreshCrashBtn(findViewById(R.id.btnViewCrash))
                toast("已清空崩溃日志")
            }
            .setNegativeButton("复制全部") { _, _ ->
                copyAllCrash()
            }
            .show()
    }

    /** 把完整崩溃日志复制到剪贴板，方便贴给开发者 */
    private fun copyAllCrash() {
        val all = CrashLog.tail(this, 100)
        if (all.startsWith("还没有")) {
            toast("没有崩溃记录")
            return
        }
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("崩溃日志", all))
            toast("已复制全部崩溃日志")
        } catch (e: Exception) {
            toast("复制失败：${e.message}")
        }
    }

    /** 按钮上直接标出有几条崩溃记录，用户一眼知道有没有出事 */
    private fun refreshCrashBtn(btn: Button) {
        val n = CrashLog.count(this)
        btn.text = if (n > 0) "查看崩溃日志（$n 条）" else "查看崩溃日志"
    }

    /** 只存邮箱相关字段，不关页面，供测试按钮用 */
    private fun saveMailOnly() {
        AgentFactory.prefs(this).edit()
            .putString("mailUser", etMailUser.text.toString().trim())
            .putString("mailPass", etMailPass.text.toString().trim())
            .putString("mailImap", etMailImap.text.toString().trim())
            .putString("mailSmtp", etMailSmtp.text.toString().trim())
            .putBoolean("mailAllowSend", cbMailSend.isChecked)
            .putString("netdiskToken", etNetdiskToken.text.toString().trim())
            .apply()
    }

    /** 网盘测试：只存 token，然后真调一次配额接口，通不通当场知道 */
    private fun testNetdisk() {
        val t = etNetdiskToken.text.toString().trim()
        AgentFactory.prefs(this).edit().putString("netdiskToken", t).apply()
        if (t.isEmpty()) {
            toast("先填 access_token")
            return
        }
        toast("正在连接网盘…")
        Thread {
            val r = try {
                NetdiskTool(this).execute(JSONObject().put("action", "quota"))
            } catch (e: Exception) {
                "失败：${e.message}"
            }
            runOnUiThread { AlertDialog.Builder(this).setTitle("百度网盘").setMessage(r).setPositiveButton("好", null).show() }
        }.start()
    }

    private fun testMail() {
        saveMailOnly()

        if (EmailClient.config(this) == null) {
            AlertDialog.Builder(this)
                .setTitle("还差一点")
                .setMessage(EmailClient.diagnose(this))
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        toast("正在连接邮箱…")
        Thread {
            val msg = try {
                val r = EmailClient.list(this, 3, false)
                "连接成功。\n\n最近邮件：\n$r"
            } catch (e: Exception) {
                "连接失败：\n${e.javaClass.simpleName}\n${e.message ?: ""}"
            }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("邮箱测试")
                    .setMessage(msg)
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }.start()
    }

    private fun pickModel() {
        val labels = models.map { "${it.first}   —   ${it.second}" }.toTypedArray()
        val idx = models.indexOfFirst { it.first == currentModel }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("选模型")
            .setSingleChoiceItems(labels, idx) { d, which ->
                currentModel = models[which].first
                tvModel.text = currentModel
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 接口列表：切换 / 新增 / 删除。配置文件也能改，这里只是给个入口 */
    private fun pickProvider() {
        // 先把输入框里的值存下来，免得切走后丢失
        val base = etBase.text.toString().trim()
        val key = etKey.text.toString().trim()
        if (key.isNotEmpty()) AgentConfig.updateActive(this, base, key, currentModel)

        val list = AgentConfig.providers(this)
        val cur = AgentConfig.activeId(this)
        val labels = list.map {
            (if (it.id == cur) "● " else "   ") + it.name + "  ·  " + it.id + "  ·  " + it.model
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("接口配置（共 ${list.size} 个）")
            .setItems(labels) { _, which ->
                val r = AgentConfig.setActive(this, list[which].id)
                reloadApi()
                toast(r)
            }
            .setNeutralButton("新增") { _, _ -> addProvider() }
            .setPositiveButton("删除当前") { _, _ -> removeCurrent() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addProvider() {
        val input = EditText(this).apply {
            hint = "给它起个 id，例如 local / deepseek / kimi"
            setText("p" + (AgentConfig.providers(this@SettingsActivity).size + 1))
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("新增接口")
            .setMessage("新接口会复制当前这组的地址和 Key，之后在这里改就行。")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val id = input.text.toString().trim()
                if (id.isEmpty()) {
                    toast("id 不能为空")
                    return@setPositiveButton
                }
                val cur = AgentConfig.active(this)
                val patch = JSONObject()
                    .put("id", id)
                    .put("name", id)
                    .put("baseUrl", cur.baseUrl)
                    .put("apiKey", cur.apiKey)
                    .put("model", cur.model)
                    .put("temperature", cur.temperature)
                    .put("maxTokens", cur.maxTokens)
                toast(AgentConfig.upsertProvider(this, patch))
                AgentConfig.setActive(this, id)
                reloadApi()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun removeCurrent() {
        val cur = AgentConfig.active(this)
        AlertDialog.Builder(this)
            .setMessage("删除接口「${cur.name}」？")
            .setPositiveButton("删除") { _, _ ->
                toast(AgentConfig.removeProvider(this, cur.id))
                reloadApi()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 切换/新增之后，把输入框刷新成新接口的值 */
    private fun reloadApi() {
        val api = AgentConfig.active(this)
        etBase.setText(api.baseUrl)
        etKey.setText(api.apiKey)
        currentModel = api.model
        tvModel.text = currentModel
        tvProvider.text = "当前接口：" + api.name + "（" + api.id + "）"
    }

    private fun save() {
        val base = etBase.text.toString().trim().ifEmpty { AgentFactory.DEFAULT_BASE }
        val key = etKey.text.toString().trim()

        if (key.isEmpty()) {
            toast("先填 API Key")
            return
        }

        // 接口三项写进 config.json，其余设置仍在 SharedPreferences
        AgentConfig.updateActive(this, base, key, currentModel)

        AgentFactory.prefs(this).edit()
            .putString("cityManual", etCity.text.toString().trim())
            .putString("mailUser", etMailUser.text.toString().trim())
            .putString("mailPass", etMailPass.text.toString().trim())
            .putString("mailImap", etMailImap.text.toString().trim())
            .putString("mailSmtp", etMailSmtp.text.toString().trim())
            .putBoolean("mailAllowSend", cbMailSend.isChecked)
            .putString("netdiskToken", etNetdiskToken.text.toString().trim())
            .putString("amapKey", etAmapKey.text.toString().trim())
            .putString("amapSecurity", etAmapSecurity.text.toString().trim())
            .putString("amapWebKey", etAmapWebKey.text.toString().trim())
            .putBoolean("allowDirectCall", cbDirectCall.isChecked)
            .putBoolean("allowSendSms", cbSendSms.isChecked)
            .putBoolean("notifEnabled", cbNotifEnabled.isChecked)
            .apply()

        // 城市被清空时，重新触发一次自动识别
        if (etCity.text.toString().trim().isEmpty()) {
            AgentFactory.prefs(this).edit().putLong("ipCityTs", 0L).apply()
            LocationHelper.refreshAsync(this)
        }

        ensureNotifyPermission()
        toast("已保存")
        finish()
    }

    private fun test() {
        val base = etBase.text.toString().trim().ifEmpty { AgentFactory.DEFAULT_BASE }
        val key = etKey.text.toString().trim()
        if (key.isEmpty()) {
            toast("Key 是空的")
            return
        }
        toast("正在测试…")
        val model = currentModel

        Thread {
            val msg = try {
                "通了，${model} 回复：" + LlmClient(base, key, model).ping()
            } catch (e: Exception) {
                "不通：" + (e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("测试结果")
                    .setMessage(msg)
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }.start()
    }

    private fun ensureNotifyPermission() {
        NotifyPermission.request(this)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    /** 通知读取是特殊访问，不在普通权限弹窗里，单独一个按钮 */
    private fun refreshNotifAccess() {
        val btn = findViewById<Button>(R.id.btnNotifAccess)
        val on = NotificationStore.isEnabled(this)
        btn.text = "通知读取：" + if (on) "已开启 ✓（点击可管理）" else "未开启，点我去开"
        btn.setOnClickListener {
            try {
                startActivity(android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                startActivity(android.content.Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统「通知使用权」页返回后，刷新状态；页面还没建好时跳过
        if (::permList.isInitialized) {
            renderPerms()
            refreshNotifAccess()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 渲染权限列表：每项一行，显示当前状态 + 去开按钮 */
    private fun renderPerms() {
        permList.removeAllViews()
        for (p in AppPermissions.ALL) {
            permList.addView(permRow(p))
        }
    }

    private fun permRow(p: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val has = AppPermissions.has(this, p)
        row.addView(TextView(this).apply {
            text = AppPermissions.label(p) + if (has) "  ✓" else ""
            textSize = 14f
            setTextColor(getColor(if (has) R.color.muted else R.color.ink))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(Button(this).apply {
            text = if (has) "已开" else "去开"
            isEnabled = !has
            setOnClickListener { AppPermissions.request(this@SettingsActivity, p) }
        })
        return row
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NotifyPermission.REQ) return
        if (requestCode == AppPermissions.REQ) {
            // 刷新列表里的 ✓ / 去开 状态
            renderPerms()
            val denied = grantResults.indices
                .filter { grantResults[it] != PackageManager.PERMISSION_GRANTED }
                .map { AppPermissions.label(permissions[it]) }
            if (denied.isNotEmpty()) {
                toast("${denied.joinToString("、")} 没开成。若之前拒绝过，得去系统设置里开。")
            }
        }
    }
}
