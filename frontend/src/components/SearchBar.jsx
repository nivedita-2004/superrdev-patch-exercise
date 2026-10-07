export default function SearchBar({ value, onChange }) {
  return (
    <input
      id="task-search"
      type="search"
      className="search-input"
      placeholder="Search tasks..."
      aria-label="Search tasks"
      value={value}
      onChange={(e) => onChange(e.target.value)}
    />
  );
}
