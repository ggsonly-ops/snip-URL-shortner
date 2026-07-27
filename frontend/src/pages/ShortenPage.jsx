import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../lib/api.js';
import CopyButton from '../components/CopyButton.jsx';

export default function ShortenPage() {
  const [url, setUrl] = useState('');
  const [customAlias, setCustomAlias] = useState('');
  const [ttlDays, setTtlDays] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [result, setResult] = useState(null);

  async function submit(e) {
    e.preventDefault();
    setError(null);
    setResult(null);
    setBusy(true);
    try {
      const payload = { url: url.trim() };
      if (customAlias.trim()) payload.customAlias = customAlias.trim();
      if (ttlDays) payload.ttlDays = Number(ttlDays);
      if (password) payload.password = password;

      const { data } = await api.createLink(payload);
      setResult(data);
      setUrl('');
      setCustomAlias('');
      setTtlDays('');
      setPassword('');
    } catch (err) {
      // The 429 branch is the point of surfacing errors here at all: it demonstrates
      // the token bucket end to end rather than as a filter nobody ever sees.
      if (err instanceof ApiError && err.status === 429) {
        setError(`Rate limited. Try again in ${err.retryAfterSeconds ?? 1}s.`);
      } else if (err instanceof ApiError) {
        const details = err.body?.details?.length ? ` (${err.body.details.join('; ')})` : '';
        setError(err.message + details);
      } else {
        setError('Something went wrong. Is the API running?');
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <form className="card" onSubmit={submit}>
        <h2>Shorten a URL</h2>
        <p className="sub">
          http and https only. URLs resolving to private, loopback or cloud-metadata
          addresses are rejected.
        </p>

        {error && <div className="alert error">{error}</div>}

        <div className="row">
          <div className="grow-2">
            <label htmlFor="url">Long URL</label>
            <input
              id="url"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              placeholder="https://example.com/a/very/long/path"
              required
              autoFocus
            />
          </div>
        </div>

        <details className="advanced">
          <summary>Options</summary>
          <div className="row">
            <div>
              <label htmlFor="alias">Custom alias</label>
              <input
                id="alias"
                value={customAlias}
                onChange={(e) => setCustomAlias(e.target.value)}
                placeholder="my-link"
                pattern="[a-zA-Z0-9_-]{3,16}"
                title="3-16 characters: letters, digits, - or _"
              />
            </div>
            <div>
              <label htmlFor="ttl">Expires after (days)</label>
              <input
                id="ttl"
                type="number"
                min="1"
                max="3650"
                value={ttlDays}
                onChange={(e) => setTtlDays(e.target.value)}
                placeholder="never"
              />
            </div>
            <div>
              <label htmlFor="pw">Password</label>
              <input
                id="pw"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="none"
                minLength={4}
              />
            </div>
          </div>
        </details>

        <div className="actions">
          <button type="submit" disabled={busy || !url.trim()}>
            {busy ? 'Shortening…' : 'Shorten'}
          </button>
        </div>
      </form>

      {result && <ResultCard link={result} />}
    </>
  );
}

function ResultCard({ link }) {
  return (
    <div className="card">
      <h2>
        Your link
        {link.deduplicated && <span className="pill">already existed — reused</span>}
        {link.passwordProtected && <span className="pill">password protected</span>}
      </h2>
      <p className="sub">
        {link.deduplicated
          ? 'An identical URL from this API key was already shortened, so the existing code came back instead of a second row.'
          : 'Created just now.'}
      </p>

      <div className="result">
        <img className="qr" src={api.qrUrl(link.shortCode, 264)} alt={`QR code for ${link.shortUrl}`} width="132" height="132" />
        <div className="detail">
          <a className="short-url" href={link.shortUrl} target="_blank" rel="noreferrer">
            {link.shortUrl}
          </a>
          <p className="long-url">{link.longUrl}</p>

          <div className="row tight">
            <CopyButton value={link.shortUrl} />
            <Link to={`/app/analytics/${link.shortCode}`}>
              <button type="button" className="secondary">Analytics</button>
            </Link>
          </div>

          {link.expiresAt && (
            <p className="small muted" style={{ marginTop: '0.75rem' }}>
              Expires {new Date(link.expiresAt).toLocaleString()}
            </p>
          )}

          {link.id && <IdBreakdown id={link.id} />}
        </div>
      </div>
    </div>
  );
}

/**
 * Shows the Snowflake id decomposed. Not decoration: it makes the id scheme
 * inspectable, so "timestamp, machine id, sequence, no coordination" is something you
 * can point at rather than assert.
 */
function IdBreakdown({ id }) {
  return (
    <details className="advanced">
      <summary>Snowflake id</summary>
      <div className="small mono muted">
        <div>raw&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; {id.raw}</div>
        <div>time&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;{new Date(id.timestamp).toISOString()}</div>
        <div>machine&nbsp;&nbsp;{id.machineId} &nbsp;<span className="muted">(which instance minted it)</span></div>
        <div>sequence {id.sequence} <span className="muted">(nth id in that millisecond)</span></div>
      </div>
    </details>
  );
}
