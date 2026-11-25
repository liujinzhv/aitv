package com.mytv.launcher.lock

import android.util.Log
import com.mytv.launcher.App
import org.json.JSONArray
import org.json.JSONObject
import java.util.Random

data class LockQuestion(
    val id: String,
    val grades: List<String>,
    val subject: String,
    val type: String,
    val question: String,
    val options: List<String> = emptyList(),
    val answer: String,
    val numeric: Boolean = false,
    val image: String? = null,
    val video: String? = null,
    val source: String = "",
    val sourceUrl: String = ""
) {
    val isChoice: Boolean get() = type == "choice" && options.isNotEmpty()
}

object QuestionBank {

    private var cached: List<LockQuestion>? = null
    private val random = Random()

    @Synchronized
    fun all(): List<LockQuestion> {
        cached?.let { return it }
        val list = try {
            parse()
        } catch (t: Throwable) {
            App.logWarn("锁屏题库读取失败", t)
            emptyList()
        }
        val result = if (list.isEmpty()) listOf(fallback()) else list
        cached = result
        return result
    }

    fun count(): Int = all().size

    fun randomFor(grade: String, excludeId: String? = null): LockQuestion {
        val all = all()
        var candidates = all.filter { grade in it.grades }
        if (candidates.isEmpty()) candidates = all
        if (excludeId != null && candidates.size > 1) {
            candidates = candidates.filter { it.id != excludeId }
        }
        return candidates[random.nextInt(candidates.size)]
    }

    /**
     * 每次展示选择题时打乱选项顺序，并重新计算正确答案字母，
     * 避免孩子靠“记住选 B”来答题。
     */
    fun randomizeChoices(question: LockQuestion): LockQuestion {
        if (!question.isChoice) return question
        val answerIndex = "ABCD".indexOf(question.answer.trim().uppercase())
        if (answerIndex !in question.options.indices) return question
        val correctText = question.options[answerIndex]
        var options = question.options.shuffled(random)
        if (question.options.size > 1 && options == question.options) {
            options = question.options.drop(1) + question.options.first()
        }
        val newAnswerIndex = options.indexOfFirst { it == correctText }
        if (newAnswerIndex < 0) return question
        val newAnswer = ('A'.code + newAnswerIndex).toChar().toString()
        return question.copy(options = options, answer = newAnswer)
    }

    private fun parse(): List<LockQuestion> {
        val text = App.ctx.assets.open("lockscreen/questions.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val root = JSONObject(text)
        val array = root.optJSONArray("questions") ?: JSONArray()
        val list = ArrayList<LockQuestion>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val gradesArray = item.optJSONArray("grades") ?: JSONArray()
            val grades = (0 until gradesArray.length()).map { gradesArray.optString(it) }
                .filter { it.isNotBlank() }
            val optionsArray = item.optJSONArray("options")
            val options = if (optionsArray == null) emptyList() else
                (0 until optionsArray.length()).map { optionsArray.optString(it) }
            val question = item.optString("question").trim()
            val answer = item.optString("answer").trim()
            if (question.isEmpty() || answer.isEmpty() || grades.isEmpty()) continue
            list.add(
                LockQuestion(
                    id = item.optString("id", "q-$i"),
                    grades = grades,
                    subject = item.optString("subject", "综合"),
                    type = item.optString("type", "choice"),
                    question = question,
                    options = options,
                    answer = answer,
                    numeric = item.optBoolean("numeric", false),
                    image = item.optString("image").takeIf { it.isNotBlank() },
                    video = item.optString("video").takeIf { it.isNotBlank() },
                    source = item.optString("source"),
                    sourceUrl = item.optString("sourceUrl")
                )
            )
        }
        Log.d("MyTV", "锁屏题库加载 ${list.size} 题")
        return list
    }

    private fun fallback() = LockQuestion(
        id = "fallback",
        grades = listOf("学前", "小班", "中班", "大班", "一年级", "二年级", "三年级",
            "四年级", "五年级", "六年级", "七年级", "八年级", "九年级", "高一", "高二", "高三"),
        subject = "数学",
        type = "fill",
        question = "3 加 4 等于几？",
        answer = "7",
        numeric = true,
        source = "内置备用题"
    )
}
