/* 引擎 ABI 探针：模拟 Zygisk shim 的链接方式，只验「名字与类型对得上、代码真能跑」。
 *
 * 为什么不写成 Zig 单测就完事：单测里 export 的名字是自己调自己，编译器怎么改名都无所谓；
 * 一旦交给 C++ 按 `wc_engine_*` 链接，符号若是被优化掉或类型宽度变了，就只剩真机上
 * system_server 加载失败这一种发现方式了 —— 那代价太大。这里花 1 秒提前撞出来。
 *
 * 期望输出（顺序固定，CI 会比对）：
 *   version=0x00020000 ping=0x5743 rules=1 decide_clip=1 decide_apps=0 forbidden_uid=3
 */
#include <stdint.h>
#include <stdio.h>

extern uint32_t wc_engine_version(void);
extern uint32_t wc_engine_ping(void);
extern uint8_t wc_engine_rule_set(uint32_t slot, uint32_t app_uid, uint8_t kind_mask);
extern uint8_t wc_engine_decide(uint32_t app_uid, uint32_t kind);
extern uint32_t wc_engine_rule_count(void);
extern void wc_engine_rule_clear(void);

int main(void) {
    int bad = 0;

    const uint32_t ver = wc_engine_version();
    const uint32_t ping = wc_engine_ping();
    if (ver != 0x00020000u) bad = 1;
    if (ping != 0x5743u) bad = 1;

    wc_engine_rule_clear();
    const uint8_t rc = wc_engine_rule_set(0, 10200, 0x01);
    if (rc != 0) bad = 1;

    const uint32_t cnt = wc_engine_rule_count();
    if (cnt != 1) bad = 1;

    const uint8_t clip = wc_engine_decide(10200, 0);
    const uint8_t apps = wc_engine_decide(10200, 1);
    if (clip != 1) bad = 1;  /* 配了剪贴板 → 给空 */
    if (apps != 0) bad = 1;  /* 没配应用列表 → 放行（不连坐） */

    const uint8_t forbidden = wc_engine_rule_set(0, 1000, 0xFF);
    if (forbidden != 3) bad = 1; /* 系统 uid 必须被拒 */
    wc_engine_rule_clear();

    printf("version=0x%08x ping=0x%04x rules=%u decide_clip=%u decide_apps=%u forbidden_uid=%u\n",
           ver, ping, cnt, clip, apps, forbidden);
    return bad;
}
