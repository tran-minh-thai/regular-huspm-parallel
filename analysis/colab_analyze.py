# -*- coding: utf-8 -*-
# =============================================================================
# RegHUSP-Parallel — results aggregation & figures (Google Colab ready)
#
# Reads the FULL_SC*.csv experiment files, builds aggregated tables (median over
# iterations) and paper-ready figures. Every figure row holds THREE panels and is
# exported as BOTH .pdf (vector) and .png (300 dpi). All labels are in English.
#
# HOW TO RUN IN COLAB
#   1) Runtime > Run all (paste this whole file into one cell, or upload as .py).
#   2) When prompted, either it auto-finds the CSVs in mounted Google Drive, or it
#      asks you to upload the 6 files: FULL_SC1_SeqVsParallel.csv ... FULL_SC6_vsAHUSP.csv
#   3) Outputs are written under  <results>/tables/  and  <results>/figures/  and
#      also offered for download.
# =============================================================================

import os, glob, io
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
from matplotlib.ticker import LogLocator

# ----------------------------- locate / load CSVs ----------------------------
SCENARIOS = {
    "SC1": "FULL_SC1_SeqVsParallel.csv",
    "SC2": "FULL_SC2_ThreadScaling.csv",
    "SC3": "FULL_SC3_ParallelAblation.csv",
    "SC4": "FULL_SC4_Preprocess.csv",
    "SC5": "FULL_SC5_DataScaling.csv",
    "SC6": "FULL_SC6_vsAHUSP.csv",
}

def _candidate_dirs():
    dirs = [".", "results", "/content", "/content/results"]
    # common Google Drive mount locations for this project
    base = "Researching/05_RegHUSP_June2026_Parallel_HUFLIT/RegHUSP_Parallel/results"
    dirs += [f"/content/drive/MyDrive/{base}", f"/content/drive/My Drive/{base}"]
    return dirs

def load_results():
    # try Google Drive (Colab)
    try:
        from google.colab import drive  # type: ignore
        if not os.path.isdir("/content/drive/MyDrive"):
            drive.mount("/content/drive")
    except Exception:
        pass

    for d in _candidate_dirs():
        if all(os.path.isfile(os.path.join(d, f)) for f in SCENARIOS.values()):
            print(f"[load] using results in: {os.path.abspath(d)}")
            return {k: pd.read_csv(os.path.join(d, v)) for k, v in SCENARIOS.items()}, d

    # fall back to manual upload (Colab)
    try:
        from google.colab import files  # type: ignore
        print("[load] upload the 6 FULL_SC*.csv files ...")
        up = files.upload()
        data = {}
        for k, v in SCENARIOS.items():
            name = v if v in up else next((n for n in up if n.endswith(v)), None)
            if name is None:
                raise FileNotFoundError(v)
            data[k] = pd.read_csv(io.BytesIO(up[name]))
        return data, "."
    except Exception as e:
        raise SystemExit(f"Could not locate the CSV files ({e}). "
                         f"Set RESULTS_DIR manually and re-run.")

RAW, RESULTS_DIR = load_results()
TAB_DIR = os.path.join(RESULTS_DIR, "tables"); os.makedirs(TAB_DIR, exist_ok=True)
FIG_DIR = os.path.join(RESULTS_DIR, "figures"); os.makedirs(FIG_DIR, exist_ok=True)

# ----------------------------- helpers ---------------------------------------
DISPLAY = {"C8T1S5I8N5K": "SYN"}  # dataset display names (English)
def disp(name): return DISPLAY.get(name, name)

def agg(df, value="runtime_ms", keys=("dataset", "algorithm", "threads")):
    """Median over iterations for SUCCESS rows; fixed-name output columns:
    runtime_ms, preprocess_ms, peak_MB, patterns, nodes. (`value` kept for API
    compatibility — callers read whichever column they need.)"""
    ok = df[df["status"] == "SUCCESS"].copy()
    g = ok.groupby(list(keys), as_index=False).agg(
        runtime_ms=("runtime_ms", "median"),
        preprocess_ms=("preprocess_ms", "median"),
        peak_MB=("peak_heap_mb", "median"),
        patterns=("total_patterns_found", "median"),
        nodes=("explored_nodes", "median"),
    )
    return g

def save_table(df, name):
    p_csv = os.path.join(TAB_DIR, name + ".csv")
    df.to_csv(p_csv, index=False)
    try:
        with open(os.path.join(TAB_DIR, name + ".tex"), "w") as f:
            f.write(df.to_latex(index=False, float_format="%.3f"))
    except Exception:
        pass
    print(f"[table] {name}: {p_csv}")
    return df

def save_fig(fig, name):
    fig.savefig(os.path.join(FIG_DIR, name + ".pdf"), bbox_inches="tight")
    fig.savefig(os.path.join(FIG_DIR, name + ".png"), dpi=300, bbox_inches="tight")
    print(f"[figure] {name}.pdf / .png")

# paper-ready matplotlib style
plt.rcParams.update({
    "font.size": 11, "axes.titlesize": 12, "axes.labelsize": 11,
    "legend.fontsize": 9, "xtick.labelsize": 10, "ytick.labelsize": 10,
    "axes.grid": True, "grid.alpha": 0.35, "figure.dpi": 110,
    "savefig.bbox": "tight", "axes.axisbelow": True,
})
MARKERS = ["o", "s", "^", "D", "v", "P", "X"]
COLORS = plt.rcParams["axes.prop_cycle"].by_key()["color"]

# =============================================================================
# TABLES
# =============================================================================

# ---- SC1: sequential vs parallel (time + memory) ----
def table_sc1():
    g = agg(RAW["SC1"], "runtime_ms")
    rows = []
    for ds in g["dataset"].unique():
        sub = {r.algorithm: r for r in g[g["dataset"] == ds].itertuples()}
        base = sub["RHusp_seq"].runtime_ms if "RHusp_seq" in sub else np.nan
        for algo in ["RHusp_seq", "Prop_seq", "Prop_par"]:
            if algo in sub:
                r = sub[algo]
                rows.append(dict(dataset=disp(ds), algorithm=algo,
                                 time_ms=int(r.runtime_ms), peak_MB=int(r.peak_MB),
                                 patterns=int(r.patterns),
                                 speedup_vs_RHusp=round(base / r.runtime_ms, 2)))
    return save_table(pd.DataFrame(rows), "SC1_seq_vs_parallel")

# ---- SC2 / SC6 speedup table ----
def speedup_table(scn, algo, name):
    g = agg(RAW[scn], "runtime_ms")
    g = g[g["algorithm"] == algo]
    rows = []
    for ds in g["dataset"].unique():
        sub = g[g["dataset"] == ds].sort_values("threads")
        t1 = sub[sub["threads"] == 1]["runtime_ms"]
        base = float(t1.iloc[0]) if len(t1) else np.nan
        for r in sub.itertuples():
            sp = base / r.runtime_ms
            rows.append(dict(dataset=disp(ds), threads=int(r.threads),
                             median_ms=int(r.runtime_ms),
                             speedup=round(sp, 3), efficiency=round(sp / r.threads, 3)))
    return save_table(pd.DataFrame(rows), name)

# ---- SC3: parallel-technique ablation ----
def table_sc3():
    g = agg(RAW["SC3"], "runtime_ms")
    order = ["Seq_T1", "Static", "Steal", "RDLB_dense", "RDLB_rawbuf"]
    rows = []
    for ds in g["dataset"].unique():
        sub = {r.algorithm: r for r in g[g["dataset"] == ds].itertuples()}
        base = sub["Seq_T1"].runtime_ms if "Seq_T1" in sub else np.nan
        for a in order:
            if a in sub:
                r = sub[a]
                rows.append(dict(dataset=disp(ds), variant=a, median_ms=int(r.runtime_ms),
                                 speedup_vs_seq=round(base / r.runtime_ms, 2),
                                 peak_MB=int(r.peak_MB)))
    return save_table(pd.DataFrame(rows), "SC3_parallel_ablation")

# ---- SC4: preprocessing speedup ----
def table_sc4():
    g = agg(RAW["SC4"], "preprocess_ms")
    rows = []
    for ds in g["dataset"].unique():
        sub = g[g["dataset"] == ds].sort_values("threads")
        base = float(sub[sub["threads"] == 1]["preprocess_ms"].iloc[0])
        for r in sub.itertuples():
            sp = base / max(r.preprocess_ms, 1)
            rows.append(dict(dataset=disp(ds), threads=int(r.threads),
                             preprocess_ms=int(r.preprocess_ms),
                             speedup=round(sp, 3), efficiency=round(sp / r.threads, 3)))
    return save_table(pd.DataFrame(rows), "SC4_preprocess")

# ---- SC5: data scaling (RHusp_seq vs Prop_par) ----
def _frac(label):  # "KOSARAK_20pct" -> ("KOSARAK", 20)
    base, _, pc = label.rpartition("_")
    return base, int(pc.replace("pct", ""))

def table_sc5():
    g = agg(RAW["SC5"], "runtime_ms")
    rec = {}
    for r in g.itertuples():
        base, pct = _frac(r.dataset)
        rec.setdefault((base, pct), {})[r.algorithm] = r.runtime_ms
    rows = []
    for (base, pct), d in sorted(rec.items()):
        rh = d.get("RHusp_seq", np.nan); pp = d.get("Prop_par", np.nan)
        rows.append(dict(dataset=disp(base), size_pct=pct,
                         RHusp_seq_ms=("" if np.isnan(rh) else int(rh)),
                         Proposed_par_ms=("" if np.isnan(pp) else int(pp)),
                         gap=("" if (np.isnan(rh) or np.isnan(pp)) else round(rh / pp, 1))))
    return save_table(pd.DataFrame(rows), "SC5_data_scaling")

def table_sc6():
    g = agg(RAW["SC6"], "runtime_ms")
    rows = []
    for ds in g["dataset"].unique():
        sub = g[g["dataset"] == ds]
        def curve(a):
            s = sub[sub["algorithm"] == a].sort_values("threads")
            b = float(s[s["threads"] == 1]["runtime_ms"].iloc[0]) if len(s) else np.nan
            return s, b
        sp_s, sp_b = curve("Prop_RDLB_dense")
        ah_s, ah_b = curve("AHUS-P")
        for t in sorted(sub["threads"].unique()):
            pr = sp_s[sp_s["threads"] == t]["runtime_ms"]
            ah = ah_s[ah_s["threads"] == t]["runtime_ms"]
            pr = float(pr.iloc[0]) if len(pr) else np.nan
            ah = float(ah.iloc[0]) if len(ah) else np.nan
            rows.append(dict(dataset=disp(ds), threads=int(t),
                             Proposed_ms=("" if np.isnan(pr) else int(pr)),
                             Proposed_Sp=("" if np.isnan(pr) else round(sp_b / pr, 2)),
                             AHUSP_ms=("" if np.isnan(ah) else int(ah)),
                             AHUSP_Sp=("" if np.isnan(ah) else round(ah_b / ah, 2))))
    return save_table(pd.DataFrame(rows), "SC6_vs_AHUSP")

T_SC1 = table_sc1()
T_SC2 = speedup_table("SC2", "Prop_RDLB_dense", "SC2_thread_scaling")
T_SC3 = table_sc3()
T_SC4 = table_sc4()
T_SC5 = table_sc5()
T_SC6 = table_sc6()
for nm, t in [("SC1", T_SC1), ("SC2", T_SC2), ("SC3", T_SC3),
              ("SC4", T_SC4), ("SC5", T_SC5), ("SC6", T_SC6)]:
    print("\n=====", nm, "=====")
    try:
        from IPython.display import display; display(t)
    except Exception:
        print(t.to_string(index=False))

# =============================================================================
# FIGURES — each figure = one row of THREE panels (PDF + PNG)
# =============================================================================

def _speedup_lines(ax, table, title, ideal=True):
    for i, ds in enumerate(table["dataset"].unique()):
        s = table[table["dataset"] == ds].sort_values("threads")
        ax.plot(s["threads"], s["speedup"], marker=MARKERS[i % len(MARKERS)],
                color=COLORS[i % len(COLORS)], label=ds, linewidth=1.8)
    if ideal:
        tmax = table["threads"].max()
        ax.plot([1, tmax], [1, tmax], "--", color="gray", linewidth=1, label="Ideal")
    ax.set_xlabel("Number of threads"); ax.set_ylabel("Speedup $S_p$")
    ax.set_title(title); ax.legend()

# ---------- Figure 1: parallel scalability ----------
fig, ax = plt.subplots(1, 3, figsize=(15, 4.3))
_speedup_lines(ax[0], T_SC2, "(a) Mining speedup vs. threads")
for i, ds in enumerate(T_SC2["dataset"].unique()):
    s = T_SC2[T_SC2["dataset"] == ds].sort_values("threads")
    ax[1].plot(s["threads"], s["efficiency"], marker=MARKERS[i % len(MARKERS)],
               color=COLORS[i % len(COLORS)], label=ds, linewidth=1.8)
ax[1].axhline(1.0, ls="--", color="gray", lw=1)
ax[1].set_xlabel("Number of threads"); ax[1].set_ylabel("Efficiency $E_p$")
ax[1].set_title("(b) Parallel efficiency"); ax[1].legend()
for i, ds in enumerate(T_SC4["dataset"].unique()):
    s = T_SC4[T_SC4["dataset"] == ds].sort_values("threads")
    ax[2].plot(s["threads"], s["speedup"], marker=MARKERS[i % len(MARKERS)],
               color=COLORS[i % len(COLORS)], label=ds, linewidth=1.8)
ax[2].set_xlabel("Number of threads"); ax[2].set_ylabel("Preprocessing speedup")
ax[2].set_title("(c) Parallel preprocessing (SWU+EUCS)"); ax[2].legend()
fig.tight_layout(); save_fig(fig, "fig1_scalability")

# ---------- Figure 2: sequential baselines vs parallel + ablation ----------
fig, ax = plt.subplots(1, 3, figsize=(15, 4.3))
algos2 = ["RHusp_seq", "Prop_seq", "Prop_par"]
labels2 = ["RHusp (seq.)", "Proposed (seq.)", "Proposed (par.)"]
ds1 = list(T_SC1["dataset"].unique()); x = np.arange(len(ds1)); w = 0.26
for j, (a, lab) in enumerate(zip(algos2, labels2)):
    vals = [T_SC1[(T_SC1.dataset == d) & (T_SC1.algorithm == a)]["time_ms"].values for d in ds1]
    vals = [float(v[0]) if len(v) else np.nan for v in vals]
    ax[0].bar(x + (j - 1) * w, vals, w, label=lab, color=COLORS[j])
ax[0].set_yscale("log"); ax[0].set_xticks(x); ax[0].set_xticklabels(ds1)
ax[0].set_ylabel("Runtime (ms, log)"); ax[0].set_title("(a) Sequential vs. parallel runtime"); ax[0].legend()
for j, (a, lab) in enumerate(zip(algos2, labels2)):
    vals = [T_SC1[(T_SC1.dataset == d) & (T_SC1.algorithm == a)]["peak_MB"].values for d in ds1]
    vals = [float(v[0]) if len(v) else np.nan for v in vals]
    ax[1].bar(x + (j - 1) * w, vals, w, label=lab, color=COLORS[j])
ax[1].set_xticks(x); ax[1].set_xticklabels(ds1)
ax[1].set_ylabel("Peak heap (MB)"); ax[1].set_title("(b) Peak memory"); ax[1].legend()
variants = ["Static", "Steal", "RDLB_dense", "RDLB_rawbuf"]
vlabels = ["Static", "Work-stealing", "RDLB (dense)", "RDLB (raw buf.)"]
ds3 = list(T_SC3["dataset"].unique()); x3 = np.arange(len(ds3)); w3 = 0.2
for j, (v, lab) in enumerate(zip(variants, vlabels)):
    vals = [T_SC3[(T_SC3.dataset == d) & (T_SC3.variant == v)]["speedup_vs_seq"].values for d in ds3]
    vals = [float(z[0]) if len(z) else np.nan for z in vals]
    ax[2].bar(x3 + (j - 1.5) * w3, vals, w3, label=lab, color=COLORS[j])
ax[2].set_xticks(x3); ax[2].set_xticklabels(ds3)
ax[2].set_ylabel("Speedup vs. sequential"); ax[2].set_title("(c) Parallel-technique ablation")
_top = np.nanmax([T_SC3["speedup_vs_seq"].max(), 1.0])
ax[2].set_ylim(0, _top * 1.32); ax[2].legend(loc="upper center", ncol=2, fontsize=8)
fig.tight_layout(); save_fig(fig, "fig2_seq_vs_parallel")

# ---------- Figure 3: data scaling + AHUS-P comparison ----------
fig, ax = plt.subplots(1, 3, figsize=(15, 4.3))
def _scaling_panel(axp, base, title):
    sub = T_SC5[T_SC5["dataset"] == base].sort_values("size_pct")
    pct = sub["size_pct"].values
    for k, col, lab in [("RHusp_seq_ms", COLORS[0], "RHusp (seq.)"),
                        ("Proposed_par_ms", COLORS[2], "Proposed (par.)")]:
        y = pd.to_numeric(sub[k], errors="coerce").values
        axp.plot(pct, y, marker="o", color=col, label=lab, linewidth=1.8)
    axp.set_yscale("log"); axp.set_xlabel("Database size (%)")
    axp.set_ylabel("Runtime (ms, log)"); axp.set_title(title); axp.legend()
bases5 = list(dict.fromkeys(T_SC5["dataset"]))
_scaling_panel(ax[0], bases5[0], f"(a) Data scaling — {bases5[0]}")
_scaling_panel(ax[1], bases5[1] if len(bases5) > 1 else bases5[0],
               f"(b) Data scaling — {bases5[1] if len(bases5)>1 else bases5[0]}")
# (c) Proposed vs AHUS-P runtime at max threads (cost of regularity)
g6 = agg(RAW["SC6"], "runtime_ms"); tmax6 = g6["threads"].max()
ds6 = [disp(d) for d in RAW["SC6"]["dataset"].unique()]
x6 = np.arange(len(ds6)); w6 = 0.36
for j, (a, lab, col) in enumerate([("Prop_RDLB_dense", "Proposed (par.)", COLORS[2]),
                                   ("AHUS-P", "AHUS-P (par.)", COLORS[3])]):
    vals = []
    for draw in RAW["SC6"]["dataset"].unique():
        s = g6[(g6.dataset == draw) & (g6.algorithm == a) & (g6.threads == tmax6)]["runtime_ms"]
        vals.append(float(s.iloc[0]) if len(s) else np.nan)
    ax[2].bar(x6 + (j - 0.5) * w6, vals, w6, label=lab, color=col)
ax[2].set_yscale("log"); ax[2].set_xticks(x6); ax[2].set_xticklabels(ds6)
ax[2].set_ylabel("Runtime (ms, log)")
ax[2].set_title(f"(c) Proposed vs. AHUS-P (T={int(tmax6)})"); ax[2].legend()
fig.tight_layout(); save_fig(fig, "fig3_scaling_vs_baselines")

# =============================================================================
# SC7 (optional) — sensitivity of the granularity threshold tau
# Reads FULL_SC7_TauSensitivity.csv (produced by test.RunTauSensitivity), which
# already stores per-tau medians. Emits a table normalized to tau=64 and fig4.
# =============================================================================
def sc7_tau():
    path = os.path.join(RESULTS_DIR, "FULL_SC7_TauSensitivity.csv")
    if not os.path.isfile(path):
        print("[SC7] FULL_SC7_TauSensitivity.csv not found — skipping "
              "(run: java ... test.RunTauSensitivity).")
        return
    df = pd.read_csv(path)
    df["dataset"] = df["dataset"].map(disp)
    taus = sorted(df["tau"].unique())

    # normalized runtime T(tau)/T(64) per dataset
    rows = []
    for ds in df["dataset"].unique():
        s = df[df["dataset"] == ds]
        b = s[s["tau"] == 64]["median_ms"]
        if not len(b):
            continue
        base = float(b.iloc[0])
        rec = {"dataset": ds}
        for t in taus:
            v = s[s["tau"] == t]["median_ms"]
            rec[f"tau_{int(t)}"] = round(float(v.iloc[0]) / base, 3) if len(v) else np.nan
        rows.append(rec)
    save_table(pd.DataFrame(rows), "SC7_tau_sensitivity")

    # figure: normalized runtime vs tau (log-2 x-axis), one line per dataset
    fig, ax = plt.subplots(1, 1, figsize=(6.4, 4.3))
    for i, ds in enumerate(df["dataset"].unique()):
        s = df[df["dataset"] == ds].sort_values("tau")
        b = s[s["tau"] == 64]["median_ms"]
        if not len(b):
            continue
        base = float(b.iloc[0])
        ax.plot(s["tau"], s["median_ms"] / base, marker=MARKERS[i % len(MARKERS)],
                color=COLORS[i % len(COLORS)], label=ds, linewidth=1.8)
    ax.axhline(1.0, ls="--", color="gray", lw=1)
    ax.set_xscale("log", base=2); ax.set_xticks(taus)
    ax.get_xaxis().set_major_formatter(plt.ScalarFormatter())
    ax.set_xlabel(r"Granularity threshold $\tau$")
    ax.set_ylabel(r"Runtime relative to $\tau=64$")
    ax.set_title("SC7 — sensitivity to the granularity threshold")
    ax.legend()
    fig.tight_layout(); save_fig(fig, "fig4_tau_sensitivity")

sc7_tau()

print("\nDONE. Tables in:", TAB_DIR, "| Figures in:", FIG_DIR)

# ---- offer downloads in Colab ----
try:
    from google.colab import files  # type: ignore
    for f in glob.glob(os.path.join(FIG_DIR, "*")) + glob.glob(os.path.join(TAB_DIR, "*")):
        files.download(f)
except Exception:
    pass
