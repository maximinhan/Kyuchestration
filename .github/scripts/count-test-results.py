# 몇 개가 돌았고 몇 개를 건너뛰었는지 모듈마다 적는다. 건너뛴 시험이 조용히 늘면 초록이 무엇을 말하는지 흐려진다 —
# 윈도우에서 무엇이 실제로 돌았는지가 여기서 보인다. 시험 결과가 없는 모듈(윈도우의 core)은 그 사실을 적는다.
#
# desktop/ 에서 부른다. Desktop CI 의 맥 · 우분투 잡과 윈도우 잡이 함께 쓴다 — 둘이 각자 적으면 한쪽만 낡는다.
import glob
import re

for module in ["protocol", "diagnostics", "core", "ui", "app"]:
    reports = glob.glob(f"{module}/build/test-results/test/*.xml")
    if not reports:
        print(f"{module}: 시험 결과 없음(건너뜀)")
        continue
    total = skipped = failed = unreadable = 0
    for report in reports:
        head = open(report, encoding="utf-8").read(4000)
        found = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', head)
        # 모양이 다른 보고서 하나 때문에 이 걸음이 죽으면 나머지 모듈의 수가 사라진다. 세지 못한 것은 따로 센다.
        if found is None:
            unreadable += 1
            print(f"{module}: 읽지 못한 보고서 {report}")
            continue
        total += int(found[1]); skipped += int(found[2]); failed += int(found[3]) + int(found[4])
    print(f"{module}: 시험 {total} · 건너뜀 {skipped} · 실패 {failed}" + (f" · 읽지 못한 보고서 {unreadable}" if unreadable else ""))
