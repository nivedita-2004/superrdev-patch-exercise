const API_BASE = '/api';

export async function fetchTasks({ query = '', status = '', page = 1, pageSize = 10, signal }) {
  const params = new URLSearchParams();
  const normalizedQuery = query.trim();
  if (normalizedQuery) params.set('q', normalizedQuery);
  if (status) params.set('status', status);
  params.set('page', String(page));
  params.set('pageSize', String(pageSize));

  const url = `${API_BASE}/tasks?${params.toString()}`;
  const response = await fetch(url, { signal });

  if (!response.ok) {
    throw new Error(`Request failed: ${response.status}`);
  }

  return response.json();
}
