#!/usr/bin/env python3
"""Build the bundled MyTV kids-lockscreen question bank.

Sources (both open-source):
  * CMATH: https://github.com/microsoft/unilm/tree/master/ReSA/math_data/cmath
    MIT licensed, 100 questions per grade for grades 1-6.
  * C-Eval: https://github.com/hkust-nlp/ceval
    CC BY-NC-SA 4.0, middle/high-school subject questions.

The pre-school/kindergarten questions are short, hand-written entry questions
because the public banks above start at primary school. They are marked
"内置启蒙题（自编）". The two image questions and the short video question
are self-produced and bundled in the APK.
"""
import json
import os
import random
import re
from collections import Counter

ROOT = r"D:\code\mytv"
TMP = os.path.join(ROOT, "docs", "bank_tmp")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "lockscreen", "questions.json")

random.seed(20261124)

SUBJECT_CN = {
    "middle_school_mathematics": "数学",
    "middle_school_physics": "物理",
    "middle_school_chemistry": "化学",
    "middle_school_biology": "生物",
    "middle_school_history": "历史",
    "middle_school_geography": "地理",
    "middle_school_politics": "道德与法治",
    "high_school_mathematics": "数学",
    "high_school_physics": "物理",
    "high_school_chemistry": "化学",
    "high_school_biology": "生物",
    "high_school_chinese": "语文",
    "high_school_history": "历史",
    "high_school_geography": "地理",
    "high_school_politics": "思想政治",
}


def clean_text(value):
    if value is None:
        return ""
    text = str(value)
    text = text.replace("\r", "\n").replace("\u3000", " ")
    text = text.replace("$$", "").replace("$", "")
    text = text.replace("\\times", "×").replace("\\div", "÷")
    text = text.replace("\\leq", "≤").replace("\\geq", "≥")
    text = text.replace("\\neq", "≠").replace("\\pm", "±")

    def frac(match):
        numerator, denominator = match.group(1), match.group(2)
        if re.search(r"[+\-]", numerator):
            numerator = f"({numerator})"
        if re.search(r"[+\-]", denominator):
            denominator = f"({denominator})"
        return f"{numerator}/{denominator}"

    text = re.sub(r"\\frac\{(.+?)\}\{(.+?)\}", frac, text)
    text = re.sub(r"\\sqrt\{(.+?)\}", r"√(\1)", text)
    text = re.sub(r"\\[a-zA-Z]+", "", text)
    text = text.replace("{", "").replace("}", "")
    text = re.sub(r"\n{3,}", "\n\n", text)
    text = re.sub(r"[ \t]{2,}", " ", text)
    return text.strip()


def numeric_answer(value):
    return bool(re.fullmatch(r"-?\d+(?:\.\d+)?", str(value).strip()))


def same_format(number, like):
    if "." in like:
        decimals = len(like.split(".")[1])
        return f"{number:.{decimals}f}"
    return str(int(number))


def make_choice(answer_text):
    """Create four numeric options around a numeric answer."""
    if not numeric_answer(answer_text):
        return None
    answer = float(answer_text)
    step = 1.0
    if abs(answer) >= 100:
        step = 10.0
    deltas = [1, -1, 2, 3]
    candidates = []
    for delta in deltas:
        value = answer + delta * step
        if value == answer:
            continue
        formatted = same_format(value, answer_text)
        if formatted not in candidates:
            candidates.append(formatted)
    while len(candidates) < 3:
        value = answer + (len(candidates) + 4) * step
        candidates.append(same_format(value, answer_text))
    options = [answer_text] + candidates[:3]
    random.shuffle(options)
    correct = "ABCD"[options.index(answer_text)]
    return options, correct


def add_question(bank, **kwargs):
    item = {
        "id": kwargs["id"],
        "grades": kwargs["grades"],
        "subject": kwargs.get("subject", "综合"),
        "type": kwargs.get("type", "choice"),
        "question": kwargs["question"].strip(),
        "answer": str(kwargs["answer"]).strip(),
        "numeric": kwargs.get("numeric", False),
        "source": kwargs["source"],
        "sourceUrl": kwargs.get("sourceUrl", ""),
    }
    if kwargs.get("options"):
        item["options"] = kwargs["options"]
    if kwargs.get("image"):
        item["image"] = kwargs["image"]
    if kwargs.get("video"):
        item["video"] = kwargs["video"]
    bank.append(item)


# ---------------------------------------------------------------- CMATH grades 1-6
with open(os.path.join(TMP, "cmath.jsonl"), encoding="utf-8") as fh:
    cmath_rows = [json.loads(line) for line in fh if line.strip()]

by_grade = Counter()
bank = []
for row in cmath_rows:
    grade = int(row["grade"])
    label = f"{'一二三四五六'[grade - 1]}年级"
    answer = str(row["answer"]).strip()
    question = clean_text(row["question"])
    if not question or not answer:
        continue
    by_grade[grade] += 1
    idx = by_grade[grade]

    # One third become four-option questions; the rest stay fill-in so both
    # answer modes are exercised at every primary-school grade.
    choice = make_choice(answer) if idx % 3 == 0 else None
    if choice:
        options, correct = choice
        add_question(
            bank,
            id=f"cmath-g{grade}-{idx:03d}",
            grades=[label],
            subject="数学",
            type="choice",
            question=question,
            options=options,
            answer=correct,
            source="CMATH（MIT）",
            sourceUrl="https://github.com/microsoft/unilm/tree/master/ReSA/math_data/cmath",
        )
    else:
        add_question(
            bank,
            id=f"cmath-g{grade}-{idx:03d}",
            grades=[label],
            subject="数学",
            type="fill",
            question=question,
            answer=answer,
            numeric=numeric_answer(answer),
            source="CMATH（MIT）",
            sourceUrl="https://github.com/microsoft/unilm/tree/master/ReSA/math_data/cmath",
        )

# ---------------------------------------------------------------- C-Eval grades 7-12
try:
    import pyarrow.parquet as pq
except Exception as exc:  # pragma: no cover - build environment hint
    raise SystemExit("pyarrow is required: python -m pip install pyarrow") from exc

ceval_bank = []
for subject_key, subject_cn in SUBJECT_CN.items():
    path = os.path.join(TMP, f"{subject_key}.parquet")
    if not os.path.exists(path):
        continue
    table = pq.read_table(path).to_pydict()
    rows = zip(
        table.get("question", []),
        table.get("A", []),
        table.get("B", []),
        table.get("C", []),
        table.get("D", []),
        table.get("answer", []),
    )
    for q, a, b, c, d, ans in rows:
        question = clean_text(q)
        options = [clean_text(a), clean_text(b), clean_text(c), clean_text(d)]
        answer = str(ans or "").strip()
        if not question or answer not in ("A", "B", "C", "D"):
            continue
        if any(not option for option in options):
            continue
        if len(set(options)) != 4:
            continue
        # Keep passages within a reasonable full-screen size. Very long
        # reading passages still work, but they slow down browsing.
        if len(question) > 900:
            continue
        ceval_bank.append((subject_cn, question, options, answer, subject_key.startswith("middle_")))

for idx, (subject, question, options, answer, is_middle) in enumerate(ceval_bank, start=1):
    # The source only labels middle/high school, not an exact year.
    grades = ["七年级", "八年级", "九年级"] if is_middle else ["高一", "高二", "高三"]
    add_question(
        bank,
        id=f"ceval-{idx:04d}",
        grades=grades,
        subject=subject,
        type="choice",
        question=question,
        options=options,
        answer=answer,
        source="C-Eval（CC BY-NC-SA 4.0）",
        sourceUrl="https://github.com/hkust-nlp/ceval",
    )

# ---------------------------------------------------------------- Pre-school / kindergarten
ENTRY_SOURCE = "内置启蒙题（自编）"

entry = [
    # 学前
    ("学前", "综合", "choice", "太阳是什么颜色？", ["红色", "蓝色", "绿色"], "A", ""),
    ("学前", "综合", "choice", "下面哪个是小动物？", ["小猫", "桌子", "苹果"], "A", ""),
    ("学前", "数学", "choice", "数一数，图片中一共有几个苹果？", ["3个", "4个", "5个", "6个"], "C", "lockscreen/apples.jpg"),
    ("学前", "数学", "choice", "一只手有几根手指？", ["3根", "4根", "5根"], "C", ""),
    ("学前", "安全", "choice", "下雨天出门要带什么？", ["雨伞", "扇子", "手套"], "A", ""),
    ("学前", "综合", "choice", "哪个水果是黄色的？", ["香蕉", "苹果", "葡萄"], "A", ""),
    ("学前", "安全", "choice", "过马路时要看什么灯？", ["红绿灯", "台灯", "路灯"], "A", ""),
    ("学前", "数学", "choice", "数字 1 的后面是几？", ["2", "3", "4"], "A", ""),
    ("学前", "数学", "choice", "哪个形状没有角？", ["圆形", "正方形", "三角形"], "A", ""),
    ("学前", "习惯", "choice", "早上起床后应该先做什么？", ["刷牙洗脸", "继续睡觉", "看电视"], "A", ""),

    # 小班
    ("小班", "数学", "choice", "2 加 1 等于几？", ["2", "3", "4"], "B", ""),
    ("小班", "数学", "choice", "5 减 2 等于几？", ["2", "3", "4"], "B", ""),
    ("小班", "综合", "choice", "哪种动物会喵喵叫？", ["小猫", "小狗", "小鸭"], "A", ""),
    ("小班", "数学", "choice", "图片中哪个形状是红色的圆形？", ["圆形", "正方形", "三角形"], "A", "lockscreen/shapes.jpg"),
    ("小班", "常识", "choice", "一个星期有几天？", ["5天", "6天", "7天"], "C", ""),
    ("小班", "常识", "choice", "下面哪个是交通工具？", ["公交车", "椅子", "香蕉"], "A", ""),
    ("小班", "数学", "choice", "数字 3 的前面是几？", ["1", "2", "4"], "B", ""),
    ("小班", "常识", "choice", "冬天应该穿什么？", ["棉衣", "短袖", "凉鞋"], "A", ""),
    ("小班", "常识", "choice", "下面哪个不是水果？", ["白菜", "苹果", "香蕉"], "A", ""),
    ("小班", "数学", "choice", "1、2、3，后面接着是几？", ["4", "5", "6"], "A", ""),

    # 中班
    ("中班", "数学", "choice", "4 加 3 等于几？", ["6", "7", "8"], "B", ""),
    ("中班", "数学", "choice", "9 减 4 等于几？", ["4", "5", "6"], "B", ""),
    ("中班", "常识", "choice", "哪种动物生活在水中？", ["鱼", "小鸟", "小猫"], "A", ""),
    ("中班", "数学", "choice", "10 的一半是多少？", ["4", "5", "6"], "B", ""),
    ("中班", "数学", "choice", "哪个形状有三条边？", ["三角形", "圆形", "正方形"], "A", ""),
    ("中班", "数学", "choice", "一盒彩笔有 8 支，用了 3 支，还剩几支？", ["4支", "5支", "6支"], "B", ""),
    ("中班", "常识", "choice", "下面哪个时间表示上午？", ["8点", "14点", "20点"], "A", ""),
    ("中班", "常识", "choice", "太阳从哪边升起？", ["东边", "南边", "西边"], "A", ""),
    ("中班", "数学", "choice", "3、6、9，下一个数是几？", ["10", "11", "12"], "C", ""),
    ("中班", "习惯", "choice", "下面哪个是好习惯？", ["饭前洗手", "挑食", "熬夜"], "A", ""),

    # 大班
    ("大班", "数学", "choice", "7 加 8 等于几？", ["14", "15", "16"], "B", ""),
    ("大班", "数学", "choice", "20 减 6 等于几？", ["13", "14", "15"], "B", ""),
    ("大班", "数学", "choice", "15 里面有几个十和几个一？", ["1个十和5个一", "5个十和1个一", "1个十和1个一"], "A", ""),
    ("大班", "数学", "choice", "看视频，数一数一共出现了几个红球？", ["3个", "4个", "5个", "6个"], "C", "raw:lock_count"),
    ("大班", "数学", "choice", "小明有 12 颗糖，给妹妹 4 颗，还剩几颗？", ["6颗", "7颗", "8颗"], "C", ""),
    ("大班", "常识", "choice", "一年有几个月？", ["10个月", "11个月", "12个月"], "C", ""),
    ("大班", "常识", "choice", "哪种动物会下蛋？", ["母鸡", "小狗", "老虎"], "A", ""),
    ("大班", "科学", "choice", "水在多少摄氏度会结冰？", ["0度", "50度", "100度"], "A", ""),
    ("大班", "常识", "choice", "下面哪个不是交通工具？", ["飞机", "火车", "书包"], "C", ""),
    ("大班", "数学", "choice", "2、4、6、8，下一个数是几？", ["9", "10", "11"], "B", ""),
]

for i, (grade, subject, qtype, question, options, answer, media) in enumerate(entry, start=1):
    image = media if media.startswith("lockscreen/") else None
    video = media if media.startswith("raw:") else None
    add_question(
        bank,
        id=f"entry-{i:03d}",
        grades=[grade],
        subject=subject,
        type=qtype,
        question=question,
        options=options,
        answer=answer,
        image=image,
        video=video,
        source=ENTRY_SOURCE,
    )

bank.sort(key=lambda q: (q["grades"][0], q["id"]))
payload = {
    "version": 1,
    "note": "MyTV 儿童锁屏离线题库；题目来自 CMATH（MIT）与 C-Eval（CC BY-NC-SA 4.0），学前/幼儿园为自编启蒙题。",
    "sources": [
        {
            "name": "CMATH",
            "license": "MIT",
            "url": "https://github.com/microsoft/unilm/tree/master/ReSA/math_data/cmath",
        },
        {
            "name": "C-Eval",
            "license": "CC BY-NC-SA 4.0",
            "url": "https://github.com/hkust-nlp/ceval",
        },
    ],
    "questions": bank,
}
os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", encoding="utf-8") as fh:
    json.dump(payload, fh, ensure_ascii=False, separators=(",", ":"))
print("questions", len(bank), "bytes", os.path.getsize(OUT))
print("by grade", Counter(q["grades"][0] for q in bank))
print("choice", sum(1 for q in bank if q["type"] == "choice"),
      "fill", sum(1 for q in bank if q["type"] == "fill"),
      "media", sum(1 for q in bank if q.get("image") or q.get("video")))
