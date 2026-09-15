package com.branchbase.core

/**
 * 远端可达性的**纯判定**：把「当前默认网络长什么样」压成三档，并回答
 * 「这次变化要不要丢弃旧结论、重新探测远端」。
 *
 * ## 为什么单独抽出来
 *
 * 这里的每个判断都是「写错了不报错、真机上才发作」的类型：
 * - 把「VPN 接入」和「换了一张网」判成同一种变化，就会在用户刚连上梯子时漏掉那次
 *   本该做的重探 —— 症状正是「App 先开着（没梯子）→ 再开梯子 → 远端还是打不开」；
 * - 把「同一个网络的能力抖动」也判成需要重探（比如带宽变化、计费标记变化），
 *   会在弱网下反复重置连接池、反复打 `/user`，反而把本来可用的连接折腾没。
 *
 * 系统回调只负责取事实（[NetworkSnapshot]），判断全部收在这里，于是能被纯 JVM 单测钉住
 * （见 `ReachabilityPolicyTest`）。
 */

/** 当前默认网络的三档类型 —— 判定只关心这三档。 */
enum class NetworkKind(val label: String) {
    /**
     * 没有默认网络，或默认网络**未通过校验**（连上了但出不了网 / 门户未登录）。
     *
     * 判定用 `NET_CAPABILITY_VALIDATED` 而不是「有没有网」：Android 的「已连接」
     * 只说明链路在，不代表能到互联网。**已接入 VPN 的网络除外** —— 它的 VALIDATED
     * 是继承来的、建链初期可能还没写上，见 [NetworkSnapshot.kind]。
     */
    OFFLINE("离线"),

    /** 有可用出口，且**没有**经过 VPN —— 也就是「未接入 VPN」。 */
    DIRECT("直连"),

    /** 有可用出口，且默认网络带 `TRANSPORT_VPN` —— 也就是「已接入 VPN」。 */
    VPN("VPN"),
}

/** 需要重新探测远端的原因。枚举只增不减，便于日志与单测精确断言。 */
enum class ReprobeReason(val label: String) {
    /** 从「出不了网」恢复到有可用出口。 */
    RESTORED("网络恢复"),

    /** 未接入 VPN → 接入 VPN。 */
    VPN_UP("VPN 接入"),

    /** 接入 VPN → 断开 VPN。 */
    VPN_DOWN("VPN 断开"),

    /** 有网，但换到了另一张网（Wi-Fi ↔ 移动数据、换 Wi-Fi）。 */
    SWITCHED("切换网络"),
}

/**
 * 一次网络快照：判定所需的**全部事实**。
 *
 * 刻意只放原始事实、不放判定结论 —— 结论一律走 [kind]，这样「取事实」与「下判断」
 * 不会各写一份口径。
 *
 * @param hasDefaultNetwork 是否有默认网络（`onAvailable` / `onLost` 维护）
 * @param validated 默认网络是否带 `NET_CAPABILITY_VALIDATED`（真能出网）
 * @param vpn 默认网络是否带 `TRANSPORT_VPN`
 * @param metered 是否计费网络（预加载策略共用；本判定不看它）
 * @param networkHandle 默认网络句柄：**换网时必变**，用来识别「不是变化、只是同网抖动」
 */
data class NetworkSnapshot(
    val hasDefaultNetwork: Boolean,
    val validated: Boolean,
    val vpn: Boolean,
    val metered: Boolean,
    val networkHandle: Long,
) {
    /** 三档判定。 */
    val kind: NetworkKind
        get() = when {
            !hasDefaultNetwork -> NetworkKind.OFFLINE
            // VPN 先判、且不看 VALIDATED：VPN 网络的 VALIDATED 是从底层网络**继承**来的，
            // 刚建链的那一小段时间可能还没写上。若因此把「已接入 VPN」判成离线，
            // 就会漏掉 VPN_UP —— 那恰恰是本机制最该抓到的一档。
            // VPN 本身就是一次有意的出口选择，先算在线；真能不能到 GitHub 由账号探测回答。
            vpn -> NetworkKind.VPN
            validated -> NetworkKind.DIRECT
            else -> NetworkKind.OFFLINE
        }

    /** 是否有可用出口（离线以外都算）。 */
    val online: Boolean get() = kind != NetworkKind.OFFLINE

    /** 日志里的一行描述：哪一档 + 句柄 + 是否计费。 */
    val label: String
        get() = if (!online) {
            kind.label
        } else {
            "${kind.label}(handle=$networkHandle${if (metered) "，计费" else "，不计费"})"
        }

    companion object {
        /** 进程刚起、还没拿到网络事实时的保守基线：当作离线。 */
        val OFFLINE = NetworkSnapshot(
            hasDefaultNetwork = false,
            validated = false,
            vpn = false,
            metered = true,
            networkHandle = 0L,
        )
    }
}

/**
 * 判定这次网络变化要不要重新探测远端。
 *
 * 返回 null = 不需要，三种情况：
 * 1. [previous] 为空 —— 进程内第一次拿到网络事实，客户端本来就是新建的，
 *    没有陈旧结论可丢（也避免启动瞬间白打一次探测）；
 * 2. 新态也没有出口 —— 没得探，等恢复；
 * 3. 同一个网络的非实质变化 —— 带宽 / 计费标记抖动。
 *
 * VPN 接入 / 断开排在「换网」前面：挂上 VPN 时默认网络句柄也会变，
 * 但原因必须记成 `VPN_UP` —— 那正是本机制存在的理由。
 */
fun decideReprobe(previous: NetworkSnapshot?, next: NetworkSnapshot): ReprobeReason? {
    if (previous == null) return null
    if (!next.online) return null
    if (!previous.online) return ReprobeReason.RESTORED
    if (previous.vpn != next.vpn) {
        return if (next.vpn) ReprobeReason.VPN_UP else ReprobeReason.VPN_DOWN
    }
    if (previous.networkHandle != next.networkHandle) return ReprobeReason.SWITCHED
    return null
}
