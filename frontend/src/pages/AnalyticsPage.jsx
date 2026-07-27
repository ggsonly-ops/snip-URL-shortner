import { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import {
  ResponsiveContainer, LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip,
  BarChart, Bar,
} from 'recharts';
import { api, ApiError } from '../lib/api.js';

const AXIS = { fontSize: 11, fill: 'var(--muted)' };

export default function AnalyticsPage() {
  const { code } = useParams();
  const [days, setDays] = useState(30);
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    api.analytics(code, days)
      .then(({ data: d }) => { if (!cancelled) setData(d); })
      .catch((err) => {
        if (!cancelled) setError(err instanceof ApiError ? err.message : 'Could not load analytics');
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [code, days]);

  if (loading) return <div className="card"><p className="empty">Loading analytics…</p></div>;
  if (error) {
    return (
      <div className="card">
        <div className="alert error">{error}</div>
        <Link to="/app/links">Back to my links</Link>
      </div>
    );
  }
  if (!data) return null;

  const peak = data.clicksPerDay.reduce((max, d) => Math.max(max, d.clicks), 0);

  return (
    <>
      <div className="card">
        <h2>/{data.shortCode}</h2>
        <p className="sub">{data.longUrl}</p>
        <div className="row tight">
          <div style={{ flex: '0 0 auto' }}>
            <label htmlFor="win">Window</label>
            <select id="win" value={days} onChange={(e) => setDays(Number(e.target.value))}>
              <option value={7}>Last 7 days</option>
              <option value={30}>Last 30 days</option>
              <option value={90}>Last 90 days</option>
              <option value={365}>Last year</option>
            </select>
          </div>
        </div>
      </div>

      <div className="stats">
        <Stat label="Total clicks" value={data.totalClicks.toLocaleString()} />
        <Stat label={`Clicks in ${data.windowDays}d`} value={data.clicksPerDay.reduce((a, d) => a + d.clicks, 0).toLocaleString()} />
        <Stat label="Busiest day" value={peak.toLocaleString()} />
        <Stat label="Countries" value={data.topCountries.length} />
      </div>

      {data.geoDataSynthetic && (
        <div className="alert warn">
          Country data is <strong>synthetic</strong>. No GeoIP database is configured, so
          countries are derived from the client IP for demo purposes only. Set
          <code> app.analytics.geoip-database</code> to a GeoLite2-Country.mmdb for real
          values.
        </div>
      )}

      <div className="card">
        <h2>Clicks over time</h2>
        <p className="sub">
          Gap-filled server-side with <span className="mono">generate_series</span>: days
          with no clicks are real zeroes, not missing points a line chart would smooth over.
        </p>
        <div className="chart-wrap">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={data.clicksPerDay} margin={{ top: 8, right: 8, bottom: 0, left: -18 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
              <XAxis dataKey="day" tick={AXIS} tickLine={false} minTickGap={28}
                     tickFormatter={(d) => d.slice(5)} />
              <YAxis tick={AXIS} tickLine={false} allowDecimals={false} />
              <Tooltip contentStyle={tooltipStyle} />
              <Line type="monotone" dataKey="clicks" stroke="var(--accent)" strokeWidth={2} dot={false} />
            </LineChart>
          </ResponsiveContainer>
        </div>
      </div>

      <div className="two-col">
        <TopChart title="Top countries" rows={data.topCountries} />
        <TopList title="Top referrers" rows={data.topReferrers} />
        <TopChart title="Devices" rows={data.devices} />
        <TopChart title="Browsers" rows={data.browsers} />
        <TopChart title="Operating systems" rows={data.operatingSystems} />
      </div>

      <p className="small muted" style={{ textAlign: 'center' }}>
        <Link to="/app/links">Back to my links</Link>
      </p>
    </>
  );
}

const tooltipStyle = {
  background: 'var(--surface)',
  border: '1px solid var(--border)',
  borderRadius: 8,
  fontSize: 12,
  color: 'var(--text)',
};

function Stat({ label, value }) {
  return (
    <div className="stat">
      <div className="label">{label}</div>
      <div className="value">{value}</div>
    </div>
  );
}

function TopChart({ title, rows }) {
  return (
    <div className="card">
      <h2>{title}</h2>
      {rows.length === 0 ? (
        <p className="empty">No data yet.</p>
      ) : (
        <div className="chart-wrap" style={{ height: Math.max(140, rows.length * 30) }}>
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={rows} layout="vertical" margin={{ top: 4, right: 12, bottom: 0, left: 8 }}>
              <XAxis type="number" tick={AXIS} tickLine={false} allowDecimals={false} />
              <YAxis type="category" dataKey="name" tick={AXIS} tickLine={false} width={92} />
              <Tooltip contentStyle={tooltipStyle} cursor={{ fill: 'var(--border)' }} />
              <Bar dataKey="count" fill="var(--accent)" radius={[0, 4, 4, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  );
}

function TopList({ title, rows }) {
  return (
    <div className="card">
      <h2>{title}</h2>
      {rows.length === 0 ? (
        <p className="empty">No data yet.</p>
      ) : (
        <div className="table-wrap">
          <table>
            <tbody>
              {rows.map((r) => (
                <tr key={r.name}>
                  <td className="small" title={r.name}>
                    <span className="target">{r.name}</span>
                  </td>
                  <td className="num">{r.count.toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
