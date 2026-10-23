package com.mini.agent

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * 任务管理页：列表 + 增删改。
 * 时间用系统 TimePicker，重复用多选对话框，不另做控件。
 */
class TasksActivity : Activity() {

    private lateinit var lv: ListView
    private var tasks: List<AgentTask> = emptyList()
    private val adapter = TaskAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tasks)

        lv = findViewById(R.id.lvTasks)
        lv.adapter = adapter
        lv.setOnItemClickListener { _, _, pos, _ ->
            tasks.getOrNull(pos)?.let { showEdit(it) }
        }
        lv.setOnItemLongClickListener { _, _, pos, _ ->
            tasks.getOrNull(pos)?.let { confirmDelete(it) }
            true
        }

        findViewById<Button>(R.id.btnAdd).setOnClickListener { showEdit(null) }
        findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }

        reload()

        // 先要通知权限，拿到结果再问精确闹钟，避免两个弹窗打架
        if (NotifyPermission.isGranted(this)) askExactAlarm() else NotifyPermission.request(this)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NotifyPermission.REQ) {
            val ok = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!ok) {
                AlertDialog.Builder(this)
                    .setTitle("没有通知权限")
                    .setMessage("任务到点还是会跑，但结果弹不出来，你看不到。去打开一下？")
                    .setPositiveButton("去开启") { _, _ -> NotifyPermission.openSettings(this) }
                    .setNegativeButton("先不管", null)
                    .show()
            }
        }
        askExactAlarm()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        TaskStore.migrateLegacy(this)
        tasks = TaskStore.load(this)
        adapter.notifyDataSetChanged()
        // 页面每次打开都按文件重排一遍，手改 tasks.json 也能生效
        TaskScheduler.scheduleAll(this)
    }

    /** 没这个权限，闹钟会被系统压到几十分钟后 */
    private fun askExactAlarm() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val am = getSystemService(AlarmManager::class.java) ?: return
        if (am.canScheduleExactAlarms()) return

        AlertDialog.Builder(this)
            .setTitle("需要「闹钟和提醒」权限")
            .setMessage("不给这个权限，任务可能晚半小时以上才响。\n\n去开启：设置 → 应用 → 特殊权限 → 闹钟和提醒 → 允许「飞飞」。")
            .setPositiveButton("去开启") { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    toast("没找到设置页，请手动去系统设置里开")
                }
            }
            .setNegativeButton("先不管", null)
            .show()
    }

    private fun confirmDelete(t: AgentTask) {
        AlertDialog.Builder(this)
            .setTitle("删除「${t.name}」？")
            .setMessage("删了就不会再自动跑了。")
            .setPositiveButton("删除") { _, _ ->
                TaskScheduler.cancel(this, t)
                TaskStore.remove(this, t.id)
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEdit(task: AgentTask?) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }

        val etName = EditText(this).apply {
            hint = "名称，例 今日简报、喝水提醒"
            setText(task?.name ?: "")
            setSingleLine()
        }
        box.addView(etName)

        var time = task?.time ?: "07:00"
        val tvTime = TextView(this).apply {
            text = "时间  $time"
            textSize = 16f
            setPadding(0, pad, 0, pad)
            setTextColor(getColor(R.color.accent))
        }
        tvTime.setOnClickListener {
            val parts = time.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: 7
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
            TimePickerDialog(this@TasksActivity, { _, hh, mm ->
                time = String.format(Locale.CHINA, "%02d:%02d", hh, mm)
                tvTime.text = "时间  $time"
            }, h, m, true).show()
        }
        box.addView(tvTime)

        var days = (task?.days ?: emptyList()).toMutableList()
        val btnDays = TextView(this).apply {
            text = "重复  " + daysText(days)
            textSize = 16f
            setPadding(0, 0, 0, pad)
            setTextColor(getColor(R.color.accent))
        }
        btnDays.setOnClickListener {
            val names = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
            val checked = BooleanArray(7) { (it + 1) in days }
            AlertDialog.Builder(this@TasksActivity)
                .setTitle("重复（一个都不选 = 每天）")
                .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                    if (isChecked) {
                        if ((which + 1) !in days) days.add(which + 1)
                    } else {
                        days.remove(which + 1)
                    }
                }
                .setPositiveButton("好") { _, _ -> btnDays.text = "重复  " + daysText(days) }
                .show()
        }
        box.addView(btnDays)

        val cbOnce = CheckBox(this).apply {
            text = "只跑这一次（不重复）"
            isChecked = task?.once ?: false
            setPadding(0, 0, 0, pad)
        }
        box.addView(cbOnce)

        val etPrompt = EditText(this).apply {
            hint = "到点让它做什么，例：播报今天天气和班次"
            setText(task?.prompt ?: "")
            minLines = 3
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        box.addView(etPrompt)

        val scroll = ScrollView(this).apply { addView(box) }

        AlertDialog.Builder(this)
            .setTitle(if (task == null) "新增定时任务" else "编辑任务")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                save(task, etName.text.toString(), time, days, etPrompt.text.toString(), cbOnce.isChecked)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun save(
        task: AgentTask?,
        name: String,
        time: String,
        days: List<Int>,
        prompt: String,
        once: Boolean
    ) {
        if (prompt.isBlank()) {
            toast("「做什么」不能空着，不然到点它不知道干嘛")
            return
        }

        val saved: AgentTask = if (task == null) {
            if (once) {
                TaskStore.addOnce(this, name, TaskScheduler.nextTimeOfDay(time), prompt)
            } else {
                TaskStore.add(this, name, time, days, prompt, true)
            }
        } else {
            val u = task.copy(
                name = name.trim().ifEmpty { task.name },
                time = time,
                days = if (once) emptyList() else days.distinct().sorted(),
                prompt = prompt.trim(),
                once = once,
                runAt = if (once) TaskScheduler.nextTimeOfDay(time) else 0L
            )
            TaskStore.update(this, u)
            TaskScheduler.cancel(this, task)
            u
        }

        if (saved.enabled) TaskScheduler.schedule(this, saved)
        val whenText = if (saved.once) saved.daysText() else saved.time + " " + saved.daysText()
        toast("已保存：$whenText")
        reload()
    }

    private fun daysText(days: List<Int>): String {
        if (days.isEmpty() || days.size >= 7) return "每天"
        val names = arrayOf("一", "二", "三", "四", "五", "六", "日")
        return "周" + days.sorted().joinToString("") { names[(it - 1).coerceIn(0, 6)] }
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private inner class TaskAdapter : BaseAdapter() {
        override fun getCount(): Int = tasks.size
        override fun getItem(position: Int): Any = tasks[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView
                ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            val t = tasks[position]

            val t1 = v.findViewById<TextView>(android.R.id.text1)
            val t2 = v.findViewById<TextView>(android.R.id.text2)

            val whenText = if (t.once) t.daysText() else "${t.time}  ${t.daysText()}"
            t1.text = "$whenText   ${t.name}" + if (t.enabled) "" else "   （已关闭）"
            t1.setTextColor(getColor(if (t.enabled) R.color.ink else R.color.muted))
            t1.textSize = 16f

            t2.text = t.prompt.replace("\n", " ").take(46)
            t2.setTextColor(getColor(R.color.muted))
            t2.textSize = 12f
            return v
        }
    }
}
