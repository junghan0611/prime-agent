import json,sys,re,pathlib
SP=pathlib.Path(sys.argv[1])  # directory of <probe>-<arm>.jsonl written by run.sh
PROBES=sorted({f.name.rsplit("-",1)[0] for f in SP.glob("p*-*.jsonl")})
PY_SYNTAX=re.compile(r'(^|\n)\s*(import |from \w+ import |def |async def )|print\(|await |f"[^"]*\{|f\'[^\']*\{')
DEF_CLJ=re.compile(r'\(def[n]?\s+([A-Za-z*+!_?<>=-][\w*+!_?<>=./-]*)')
DEF_PY=re.compile(r'(?m)^\s*(?:def\s+([A-Za-z_]\w*)|([A-Za-z_]\w*)\s*=)')
def load(p):
    out=[]
    for line in open(p,encoding='utf-8',errors='replace'):
        line=line.strip()
        if line:
            try: out.append(json.loads(line))
            except Exception: pass
    return out
def extract(evs):
    cells=[];results=[]
    for e in evs:
        if e.get("type")=="message_end":
            m=e.get("message") or {}
            if m.get("role")=="assistant":
                for c in m.get("content") or []:
                    if c.get("type")=="toolCall" and c.get("name")=="ipython":
                        code=(c.get("arguments") or {}).get("code")
                        if code is not None: cells.append(code)
        if e.get("type")=="turn_end":
            for tr in e.get("toolResults") or []:
                d=tr.get("details") or {}
                txt="".join(x.get("text","") for x in (tr.get("content") or []) if x.get("type")=="text")
                results.append({"status":d.get("status"),"ename":d.get("errorEname"),"text":txt})
    return cells,results
def carried(cells,arm):
    """A name bound in cell i and used again in a later cell."""
    rx=DEF_CLJ if arm=="clojure" else DEF_PY
    hits=[]
    for i,c in enumerate(cells):
        names=set()
        for m in rx.finditer(c):
            names.update(g for g in m.groups() if g)
        for nm in names:
            if len(nm)<2: continue
            for j in range(i+1,len(cells)):
                if re.search(r'(?<![\w./-])'+re.escape(nm)+r'(?![\w./-])',cells[j]):
                    hits.append((nm,i+1,j+1)); break
    return hits
def usage(evs):
    t={"in":0,"out":0,"cache":0,"cost":0.0}; seen=set()
    for e in evs:
        if e.get("type")=="message_end":
            m=e.get("message") or {}
            u=m.get("usage")
            if not u: continue
            rid=m.get("responseId")
            if rid in seen: continue
            seen.add(rid)
            t["in"]+=u.get("input",0); t["out"]+=u.get("output",0); t["cache"]+=u.get("cacheRead",0)
            t["cost"]+=(u.get("cost") or {}).get("total",0.0)
    return t
print(f"{'probe':5} {'arm':8} {'cells':5} {'cellErr':7} {'pyLeak':6} {'carried':7} {'in':>7} {'out':>6} {'cache':>7} {'cost$':>8}")
tot=0.0; rows=[]
for arm in ("python","clojure"):
    for p in PROBES:
        evs=load(SP/f"{p}-{arm}.jsonl")
        cells,results=extract(evs)
        cerr=sum(1 for r in results if r["status"]=="error")
        leak=sum(1 for c in cells if PY_SYNTAX.search(c)) if arm=="clojure" else 0
        car=carried(cells,arm); u=usage(evs); tot+=u["cost"]
        rows.append((p,arm,cells,results,car))
        print(f"{p:5} {arm:8} {len(cells):5} {cerr:7} {leak:6} {len(car):7} {u['in']:7} {u['out']:6} {u['cache']:7} {u['cost']:8.5f}")
print(f"{'TOTAL':5} {'':8} {'':5} {'':7} {'':6} {'':7} {'':7} {'':6} {'':7} {tot:8.5f}")
print()
for p,arm,cells,results,car in rows:
    ex=[f"{n}(c{i}->c{j})" for n,i,j in car][:4]
    errs=[r["ename"] for r in results if r["status"]=="error"]
    print(f"{p}/{arm}: carried={ex} errs={errs}")
