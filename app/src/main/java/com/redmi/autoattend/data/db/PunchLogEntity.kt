package com.redmi.autoattend.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 打卡轮次类型：上班 / 下班 */
enum class PunchKind {
    /** 上班打卡 */
    CLOCK_IN,

    /** 下班打卡 */
    CLOCK_OUT,

    /** 用户手动触发的打卡 */
    MANUAL,
}

/**
 * 一次打卡尝试的最终结果。
 *
 * 注意：[SUCCESS] 只能由钉钉界面的真实反馈判定，绝不能因为"点击已发出"就记成功。
 */
enum class PunchOutcome {
    /** 钉钉界面明确反馈打卡成功 */
    SUCCESS,

    /** 任一步骤失败并已重试耗尽 */
    FAILURE,

    /** 用户主动取消 */
    CANCELLED,

    /** 正在执行中的中间态，正常不应落库 */
    IN_PROGRESS,
}

/**
 * 打卡日志。
 *
 * 保留 30 天，由 [PunchLogDao.deleteOlderThan] 定期清理。
 * [evidence] 记录"成功判定依据"的原始文本，用于事后审计——这是需求里的硬性要求。
 */
@Entity(tableName = "punch_log")
data class PunchLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 计划执行的时间戳（毫秒） */
    val scheduledAt: Long,

    /** 实际开始执行的时间戳（毫秒） */
    val startedAt: Long,

    /** 结束时间戳（毫秒）；执行中断时为 0 */
    val finishedAt: Long = 0,

    /** 打卡类型 */
    val kind: PunchKind,

    /** 结果 */
    val outcome: PunchOutcome,

    /** 使用的执行通道：accessibility / root / auto */
    val channel: String,

    /** 失败发生在哪个环节，例如 "解锁"、"启动钉钉"、"点击考勤打卡"；成功时为 null */
    val failedStage: String? = null,

    /** 人类可读的失败原因或成功说明 */
    val message: String? = null,

    /**
     * 成功判定的原始证据：从钉钉界面读到的文本，或命中的 AccessibilityEvent 内容。
     * 这是"不以点击发出为成功"的审计线索。
     */
    val evidence: String? = null,

    /** 完整的逐步执行轨迹，用于排查（JSON 字符串） */
    val trace: String? = null,
)