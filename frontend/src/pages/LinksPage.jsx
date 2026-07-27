import { useEffect, useState, useCallback } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError, getApiKey } from '../lib/api.js';
import CopyButton from '../components/CopyButton.jsx';

export default function LinksPage() {
  const [links, setLinks] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [editing, setEditing] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const { data } = await api.listLinks(0, 100);
      setLinks(data.items);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not load links');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  async function remove(code) {
    if (!confirm(`Delete /${code}? The short code will stop resolving.`)) return;
    try {
      await api.deleteLink(code);
      setLinks((prev) => prev.filter((l) => l.shortCode !== code));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Delete failed');
    }
  }

  async function saveEdit(code, newUrl) {
    try {
      const { data } = await api.updateLink(code, { url: newUrl });
      setLinks((prev) => prev.map((l) => (l.shortCode === code ? data : l)));
      setEditing(null);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Update failed');
    }
  }

  return (
    <div className="card">
      <h2>My links</h2>
      <p className="sub">
        Scoped to the API key in this browser
        {getApiKey() && <span className="mono small"> ({getApiKey().slice(0, 12)}…)</span>}.
        Links created without a key are anonymous and cannot be listed or managed.
      </p>

      {error && <div className="alert error">{error}</div>}

      {loading ? (
        <p className="empty">Loading…</p>
      ) : links.length === 0 ? (
        <p className="empty">
          No links yet. <Link to="/">Shorten one</Link>.
        </p>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Short code</th>
                <th>Target</th>
                <th className="num">Clicks</th>
                <th>Expires</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {links.map((l) => (
                <tr key={l.shortCode}>
                  <td>
                    <a className="code" href={l.shortUrl} target="_blank" rel="noreferrer">
                      /{l.shortCode}
                    </a>
                    {l.passwordProtected && <span className="pill">locked</span>}
                    {!l.active && <span className="pill off">inactive</span>}
                  </td>
                  <td>
                    {editing === l.shortCode ? (
                      <InlineEdit
                        initial={l.longUrl}
                        onCancel={() => setEditing(null)}
                        onSave={(v) => saveEdit(l.shortCode, v)}
                      />
                    ) : (
                      <span className="target" title={l.longUrl}>{l.longUrl}</span>
                    )}
                  </td>
                  <td className="num">{l.clickCount.toLocaleString()}</td>
                  <td className="small muted">
                    {l.expiresAt ? new Date(l.expiresAt).toLocaleDateString() : 'never'}
                  </td>
                  <td>
                    <div className="row tight" style={{ justifyContent: 'flex-end' }}>
                      <CopyButton value={l.shortUrl} />
                      <Link to={`/app/analytics/${l.shortCode}`}>
                        <button type="button" className="secondary">Stats</button>
                      </Link>
                      <button type="button" className="secondary" onClick={() => setEditing(l.shortCode)}>
                        Edit
                      </button>
                      <button type="button" className="danger" onClick={() => remove(l.shortCode)}>
                        Delete
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function InlineEdit({ initial, onSave, onCancel }) {
  const [value, setValue] = useState(initial);
  return (
    <div className="row tight">
      <input value={value} onChange={(e) => setValue(e.target.value)} autoFocus />
      <button type="button" style={{ flex: '0 0 auto' }} onClick={() => onSave(value)}>Save</button>
      <button type="button" className="secondary" style={{ flex: '0 0 auto' }} onClick={onCancel}>Cancel</button>
    </div>
  );
}
