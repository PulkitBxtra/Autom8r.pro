"use client";

import { useEffect, useState } from "react";

// Current time for live durations ("running for 12s"). Only ticks while
// `active`, so a page with nothing running doesn't re-render every second.
export function useNow(active: boolean, intervalMs = 1000) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (!active) return;
    const timer = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(timer);
  }, [active, intervalMs]);

  return now;
}
