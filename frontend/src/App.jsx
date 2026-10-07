import { useState } from 'react';
import SearchBar from './components/SearchBar';
import StatusFilter from './components/StatusFilter';
import TaskTable from './components/TaskTable';
import { useTasks } from './hooks/useTasks';

export default function App() {
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);

  const { tasks, total, loading, error } = useTasks(query, status, page, 10);

  const totalPages = Math.ceil(total / 10);
  const handleQueryChange = (value) => {
    setQuery(value);
    setPage(1);
  };
  const handleStatusChange = (value) => {
    setStatus(value);
    setPage(1);
  };

  const isFiltered = Boolean(query.trim() || status);

  return (
    <div className="app">
      <header className="app-header">
        <h1>Task Tracker</h1>
        <p className="subtitle">Internal task management</p>
      </header>

      <div className="controls">
        <SearchBar value={query} onChange={handleQueryChange} />
        <StatusFilter value={status} onChange={handleStatusChange} />
      </div>

      <TaskTable tasks={tasks} loading={loading} error={error} isFiltered={isFiltered} />

      {totalPages > 1 && (
        <div className="pagination">
          <button
            type="button"
            aria-label="Previous page"
            disabled={loading || page <= 1}
            onClick={() => setPage((p) => Math.max(1, p - 1))}
          >
            Previous
          </button>
          <span>
            Page {page} of {totalPages}
          </span>
          <button
            type="button"
            aria-label="Next page"
            disabled={loading || page >= totalPages}
            onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
          >
            Next
          </button>
        </div>
      )}
    </div>
  );
}
