import { useState, useEffect, useRef } from 'react';
import { fetchTasks } from '../api';

export function useTasks(query, status, page, pageSize) {
  const normalizedQuery = query.trim();
  const previousQuery = useRef(normalizedQuery);
  const [data, setData] = useState({ items: [], total: 0 });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    const queryChanged = previousQuery.current !== normalizedQuery;
    previousQuery.current = normalizedQuery;

    const controller = new AbortController();
    let active = true;

    setLoading(true);
    setError(null);

    const timer = window.setTimeout(() => {
      fetchTasks({ query: normalizedQuery, status, page, pageSize, signal: controller.signal })
        .then((res) => {
          if (!active) return;
          setData({ items: res.items, total: res.total });
          setLoading(false);
        })
        .catch((err) => {
          if (!active || err.name === 'AbortError') return;
          setError(err.message);
          setLoading(false);
        });
    }, queryChanged ? 250 : 0);

    return () => {
      active = false;
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [normalizedQuery, status, page, pageSize]);

  return {
    tasks: data.items,
    total: data.total,
    loading,
    error,
  };
}
