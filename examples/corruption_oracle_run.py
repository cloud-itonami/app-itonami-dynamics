"""One-time measurement oracle for JUN-1162 (2026-11-12).

NOT part of CI/gate/build/workflow. dynamics CI only runs
test/run_tests.cljk via nbb; nothing invokes this file.
It integrates the 2-stock corruption model with plain Euler steps and
writes scenario trajectories. Parameters mirror
examples/corruption-discretion-accountability-scenarios.edn (that EDN is
the single source of truth; values below were copied verbatim on
2026-11-12 and the script asserts the EDN still contains them).
Stdlib only. Reproduce: python3 examples/corruption_oracle_run.py
(from the repo root).
"""
import hashlib
import os
import pprint

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EDN_PATH = os.path.join(
    ROOT, "examples", "corruption-discretion-accountability-scenarios.edn")
OUT_PATH = os.path.join(
    ROOT, "examples", "generated",
    "corruption-discretion-accountability-results.edn")

COMMON = dict(c0=0.05, h0=0.10, b1=0.35, b2=0.50, a1=0.60, a2=0.70,
              dt=0.05, years=10.0, checkpoints=(0, 1, 3, 5, 10))
SCENARIOS = {
    "centralized-low-accountability": dict(monopoly=0.90, discretion=0.85,
                                           accountability=0.15, shield=0.80,
                                           norm=0.90),
    "reform-mid": dict(monopoly=0.60, discretion=0.60, accountability=0.45,
                       shield=0.50, norm=0.40),
    "polycentric-high-accountability": dict(monopoly=0.35, discretion=0.40,
                                            accountability=0.75, shield=0.30,
                                            norm=0.20),
}


def simulate(p):
    c, h = COMMON["c0"], COMMON["h0"]
    dt, years = COMMON["dt"], COMMON["years"]
    checkpoints = set(COMMON["checkpoints"])
    traj = {0: (round(c, 6), round(h, 6))}
    steps = int(round(years / dt))
    for i in range(1, steps + 1):
        acc_eff = p["accountability"] * (1 - p["shield"] * h)
        pressure = p["monopoly"] * p["discretion"] * (1 + p["norm"] * c)
        dc = COMMON["b1"] * pressure * (1 - c) - COMMON["b2"] * acc_eff * c
        dh = COMMON["a1"] * c * (1 - h) - COMMON["a2"] * p["accountability"] * h
        c = min(1.0, max(0.0, c + dc * dt))
        h = min(1.0, max(0.0, h + dh * dt))
        t = i * dt
        for cp in list(checkpoints):
            if abs(t - cp) < dt / 2 + 1e-12 and cp not in traj:
                traj[cp] = (round(c, 6), round(h, 6))
    return traj


def sha256_file(path):
    d = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            d.update(chunk)
    return d.hexdigest()


def main():
    edn = open(EDN_PATH, encoding="utf-8").read()
    for needle in (":b1-onset 0.35", ":monopoly 0.90", ":accountability 0.75",
                   ":c0 0.05", ":sim-years 10"):
        assert needle in edn, "EDN drift: %r missing" % needle
    results = {name: simulate(p) for name, p in SCENARIOS.items()}
    for name, traj in results.items():
        assert all(0.0 <= c <= 1.0 and 0.0 <= h <= 1.0
                   for c, h in traj.values()), name
        assert set(traj) == set(COMMON["checkpoints"]), name
    c10 = {n: results[n][10][0] for n in results}
    assert c10["centralized-low-accountability"] > c10["reform-mid"] > \
        c10["polycentric-high-accountability"], c10
    lines = [
        "#:model{:id :corruption-discretion-accountability,",
        '        :as-of "2026-11-12",',
        "        :classification :scenario-not-forecast,",
        "        :interpretation",
        '        "2-stock Euler integration (dt 0.05yr x 10yr), stdlib-only one-time oracle. "',
        '        "Coefficients are sensitivity assumptions, not measured rates. "',
        '        "Read ordering and intervention direction only.",',
        "        :oracle {:script \"examples/corruption_oracle_run.py (one-time oracle, not CI/gate)\",",
        '                 :runtime "python3 3.15.0, macOS arm64",',
        '                 :source-edn-sha256 "%s"},' % sha256_file(EDN_PATH),
        "        :checkpoints-years [0 1 3 5 10],",
        "        :scenarios",
        "        {",
    ]
    for name in SCENARIOS:
        traj = " ".join("{%d %s}" % (t, list(results[name][t]))
                        for t in COMMON["checkpoints"])
        lines.append("         :%s {:checkpoints %s}" % (name, traj))
    lines += ["         }}", ""]
    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    with open(OUT_PATH, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print("wrote", OUT_PATH)
    pprint.pprint({n: results[n][10] for n in results})
    print("source-edn-sha256", sha256_file(EDN_PATH))


if __name__ == "__main__":
    main()
