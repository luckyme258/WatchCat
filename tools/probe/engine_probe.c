/* 引擎 ABI 探针：模拟 Zygisk shim 的链接方式，只验「名字与类型对得上、代码真能跑」。
 *
 * 为什么不写成 Zig 单测就完事：单测里 export 的名字是自己调自己，编译器怎么改名都无所谓；
 * 一旦交给 C++ 按 `wc_engine_*` 链接，符号若是被优化掉或类型宽度变了，就只剩真机上
 * system_server 加载失败这一种发现方式了 —— 那代价太大。这里花 1 秒提前撞出来。
 *
 * 2026-11-22 起（L16.2.1「业务不得放 native」）引擎的导出面只剩两个哨兵：
 * rule_set / decide / rule_count / rule_clear 已从 `export fn` 改成 `pub fn`，
 * 不再进 .so 的符号表，所以这里也**不许**再引用它们（引用就会 undefined symbol）。
 * 判定语义（并集、拒系统 uid、未知 kind 走放行）改由 `zig test zig/src/engine.zig`
 * 的 7 条用例守——那条路比 ABI 探针更适合验语义。
 *
 * 期望输出（顺序固定）：
 *   version=0x00020000 ping=0x5743
 */
#include <stdint.h>
#include <stdio.h>

extern uint32_t wc_engine_version(void);
extern uint32_t wc_engine_ping(void);

int main(void) {
    int bad = 0;

    const uint32_t ver = wc_engine_version();
    const uint32_t ping = wc_engine_ping();
    if (ver != 0x00020000u) bad = 1;
    if (ping != 0x5743u) bad = 1;

    printf("version=0x%08x ping=0x%04x\n", ver, ping);
    return bad;
}
