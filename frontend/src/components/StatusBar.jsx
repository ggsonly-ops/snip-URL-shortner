import { useEffect, useState } from 'react';
import { api } from '../lib/api.js';

/**
 * Live service state in the footer.
 *
 * <p>This exists for the Redis-outage demo: stop Redis mid-traffic and the circuit
 * breaker flips to OPEN here while redirects keep working from Postgres. It is the
 * cheapest possible way to make graceful degradation visible on a screen share.
 */
export default function StatusBar() {
  const [status, setStatus] = useState(null);

  useEffect(() => {
    let cancelled = false;
    const tick = () => api.status()
      .then(({ data }) => { if (!cancelled) setStatus(data); })
      .catch(() => { if (!cancelled) setStatus(null); });

    tick();
    const id = setInterval(tick, 5000);
    return () => { cancelled = true; clearInterval(id); };
  }, []);

  if (!status) {
    return (
      <footer className="foot">
        <div>API unreachable</div>
      </footer>
    );
  }

  const circuit = status.cache.redisCircuit;
  const healthy = circuit === 'CLOSED';

  return (
    <footer className="foot">
      <div>
        machine id <code>{status.machineId}</code>
        {' · '}redis <span className={`pill ${healthy ? 'on' : 'off'}`}>{circuit}</span>
        {' · '}cache hit ratio <code>{(status.cache.hitRatio * 100).toFixed(1)}%</code>
        {' '}<span className="muted">({status.cache.hits} hits / {status.cache.misses} misses)</span>
        {status.cache.unavailable > 0 && (
          <> {' · '}<span className="pill off">{status.cache.unavailable} degraded reads</span></>
        )}
      </div>
      <div>
        bloom {status.bloom.ready ? 'ready' : 'building'}
        {' · '}rate limit {status.rateLimitEnabled ? 'on' : 'off'}
        {' · '}analytics {status.analyticsEnabled ? 'on' : 'off'}
        {' · '}<a href="/actuator/prometheus">metrics</a>
      </div>
    </footer>
  );
}
