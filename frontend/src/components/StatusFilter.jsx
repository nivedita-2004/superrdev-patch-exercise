export default function StatusFilter({ value, onChange }) {
  return (
    <select
      id="status-filter"
      className="status-filter"
      aria-label="Filter tasks by status"
      value={value}
      onChange={(e) => onChange(e.target.value)}
    >
      <option value="">All statuses</option>
      <option value="OPEN">Open</option>
      <option value="IN_PROGRESS">In Progress</option>
      <option value="DONE">Done</option>
    </select>
  );
}
