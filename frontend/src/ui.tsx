// Reusable building blocks for the console. ResourcePanel is the workhorse: a create form + a table
// with per-row actions, driven by a small config, so each resource (tenants, machines, …) is a few
// lines rather than a bespoke component.

import { useEffect, useState } from "react";

export type Field = {
  name: string;
  label: string;
  type?: "text" | "number" | "checkbox" | "select" | "ids";
  options?: { value: string; label: string }[];
  optional?: boolean;
  placeholder?: string;
};

export type Column = {
  key: string;
  label: string;
  render?: (value: any, row: any) => any;
};

export type Action = {
  label: string;
  run: (row: any) => Promise<any>;
  confirm?: string;
};

function get(obj: any, path: string): any {
  return path.split(".").reduce((o, k) => (o == null ? o : o[k]), obj);
}

function buildPayload(fields: Field[], form: Record<string, any>): any {
  const out: Record<string, any> = {};
  for (const f of fields) {
    const raw = form[f.name];
    if (raw === undefined || raw === "") {
      if (!f.optional) out[f.name] = raw;
      continue;
    }
    if (f.type === "number") out[f.name] = Number(raw);
    else if (f.type === "checkbox") out[f.name] = Boolean(raw);
    else if (f.type === "ids")
      out[f.name] = String(raw)
        .split(",")
        .map((s) => s.trim())
        .filter(Boolean)
        .map(Number);
    else out[f.name] = raw;
  }
  return out;
}

export function ResourcePanel(props: {
  title: string;
  description?: string;
  columns: Column[];
  load: () => Promise<any>;
  createFields?: Field[];
  createLabel?: string;
  onCreate?: (payload: any) => Promise<any>;
  actions?: Action[];
  filters?: React.ReactNode;
}) {
  const [items, setItems] = useState<any[]>([]);
  const [form, setForm] = useState<Record<string, any>>({});
  const [msg, setMsg] = useState<{ kind: "err" | "ok"; text: string } | null>(null);
  const [output, setOutput] = useState<string>("");
  const [busy, setBusy] = useState(false);

  async function refresh() {
    setMsg(null);
    try {
      const r = await props.load();
      setItems(Array.isArray(r) ? r : (r?.items ?? []));
    } catch (e) {
      setMsg({ kind: "err", text: (e as Error).message });
    }
  }

  useEffect(() => {
    refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [props.title]);

  async function create() {
    if (!props.onCreate) return;
    setBusy(true);
    setMsg(null);
    try {
      const res = await props.onCreate(buildPayload(props.createFields ?? [], form));
      setForm({});
      setMsg({ kind: "ok", text: "created" });
      if (res && typeof res === "object") setOutput(JSON.stringify(res, null, 2));
      await refresh();
    } catch (e) {
      setMsg({ kind: "err", text: (e as Error).message });
    } finally {
      setBusy(false);
    }
  }

  async function runAction(a: Action, row: any) {
    if (a.confirm && !window.confirm(a.confirm)) return;
    setBusy(true);
    setMsg(null);
    try {
      const res = await a.run(row);
      setMsg({ kind: "ok", text: `${a.label} ✓` });
      if (res && typeof res === "object") setOutput(JSON.stringify(res, null, 2));
      await refresh();
    } catch (e) {
      setMsg({ kind: "err", text: (e as Error).message });
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel">
      <header>
        <h2>{props.title}</h2>
        <button className="ghost" onClick={refresh} disabled={busy}>
          refresh
        </button>
      </header>
      {props.description && <p className="hint">{props.description}</p>}

      {props.createFields && props.onCreate && (
        <div className="form">
          {props.createFields.map((f) => (
            <label key={f.name} className={f.type === "checkbox" ? "chk" : ""}>
              <span>
                {f.label}
                {f.optional ? " (optional)" : ""}
              </span>
              {f.type === "select" ? (
                <select
                  value={form[f.name] ?? ""}
                  onChange={(e) => setForm({ ...form, [f.name]: e.target.value })}
                >
                  <option value="">—</option>
                  {(f.options ?? []).map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              ) : f.type === "checkbox" ? (
                <input
                  type="checkbox"
                  checked={Boolean(form[f.name])}
                  onChange={(e) => setForm({ ...form, [f.name]: e.target.checked })}
                />
              ) : (
                <input
                  type={f.type === "number" ? "number" : "text"}
                  value={form[f.name] ?? ""}
                  placeholder={f.placeholder}
                  onChange={(e) => setForm({ ...form, [f.name]: e.target.value })}
                />
              )}
            </label>
          ))}
          <button onClick={create} disabled={busy}>
            {props.createLabel ?? "Create"}
          </button>
        </div>
      )}

      {props.filters && <div className="filters">{props.filters}</div>}
      {msg && <div className={msg.kind === "err" ? "msg err" : "msg ok"}>{msg.text}</div>}

      <div className="tablewrap">
        <table>
          <thead>
            <tr>
              {props.columns.map((c) => (
                <th key={c.key}>{c.label}</th>
              ))}
              {props.actions && <th>actions</th>}
            </tr>
          </thead>
          <tbody>
            {items.length === 0 && (
              <tr>
                <td colSpan={props.columns.length + 1} className="empty">
                  no rows
                </td>
              </tr>
            )}
            {items.map((row, i) => (
              <tr key={row.id ?? i}>
                {props.columns.map((c) => (
                  <td key={c.key}>{c.render ? c.render(get(row, c.key), row) : String(get(row, c.key) ?? "")}</td>
                ))}
                {props.actions && (
                  <td className="rowactions">
                    {props.actions.map((a) => (
                      <button key={a.label} className="ghost" onClick={() => runAction(a, row)} disabled={busy}>
                        {a.label}
                      </button>
                    ))}
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {output && (
        <div className="output">
          <div className="outputhead">
            <span>output</span>
            <button className="ghost" onClick={() => setOutput("")}>
              clear
            </button>
          </div>
          <pre>{output}</pre>
        </div>
      )}
    </section>
  );
}
