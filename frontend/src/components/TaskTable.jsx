export default function TaskTable({ tasks, loading, error, isFiltered = false }) {
  if (loading && (!tasks || tasks.length === 0)) {
    return <div className="state-message" role="status">Loading tasks...</div>;
  }

  if (error && (!tasks || tasks.length === 0)) {
    return (
      <div className="state-message error" role="alert">
        Unable to load tasks. Please try again.
      </div>
    );
  }

  if (!loading && (!tasks || tasks.length === 0)) {
    return (
      <div className="state-message" role="status">
        {isFiltered ? 'No tasks match the current search or filter criteria.' : 'No tasks available.'}
      </div>
    );
  }

  return (
    <>
      <div className="table-feedback" role="status">
        {loading ? 'Updating tasks...' : ''}
      </div>
      {error && (
        <div className="state-message error" role="alert">
          Unable to load tasks. Please try again.
        </div>
      )}
      <div className="table-wrapper">
        <table className="task-table" aria-busy={loading}>
          <thead>
            <tr>
              <th scope="col">ID</th>
              <th scope="col">Title</th>
              <th scope="col">Status</th>
              <th scope="col">Priority</th>
              <th scope="col">Assignee</th>
            </tr>
          </thead>
          <tbody>
            {tasks.map((task) => (
              <tr key={task.id}>
                <td>{task.id}</td>
                <td>
                  <div className="task-title">{task.title}</div>
                  {task.description && <div className="task-desc">{task.description}</div>}
                </td>
                <td>
                  <span className={`status-badge ${task.status.toLowerCase()}`}>{task.status}</span>
                </td>
                <td>{task.priority}</td>
                <td>{task.assignee || '\u2014'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
