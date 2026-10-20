import Foundation

// 由 test.sh 与协议 / 加密源码一起编译运行；失败时以非零码退出。
let run = TestRun()
runVectorTests(run)
runIdentityTests(run)
runPayloadTests(run)
runDisplayTests(run)
runChartTests(run)
runCursorAndStatsTests(run)
runHistoryTests(run)
runE2ETests(run)
run.finish()
