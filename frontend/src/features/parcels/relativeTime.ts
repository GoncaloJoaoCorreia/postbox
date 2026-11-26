export function relativeTime(iso: string, now: number = Date.now()): string {
  const then = new Date(iso).getTime();
  const seconds = Math.max(0, Math.round((now - then) / 1000));
  if (seconds < 45) return "just now";
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  const days = Math.round(hours / 24);
  return `${days} d ago`;
}

function timeOfDay(date: Date): string {
  return date.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit", hour12: false });
}

function isSameDay(a: Date, b: Date): boolean {
  return a.toDateString() === b.toDateString();
}

export function formatEventTime(iso: string, now: number = Date.now()): string {
  const date = new Date(iso);
  const today = new Date(now);
  const time = timeOfDay(date);
  if (isSameDay(date, today)) return `${time} today`;
  const yesterday = new Date(now - 24 * 60 * 60 * 1000);
  if (isSameDay(date, yesterday)) return `Yesterday, ${time}`;
  const day = date.toLocaleDateString("en-GB", { day: "numeric", month: "short" });
  return `${day}, ${time}`;
}

export function formatEta(iso: string, now: number = Date.now()): string {
  const date = new Date(iso);
  const today = new Date(now);
  const time = timeOfDay(date);
  if (isSameDay(date, today)) return `Today by ${time}`;
  const tomorrow = new Date(now + 24 * 60 * 60 * 1000);
  if (isSameDay(date, tomorrow)) return `Tomorrow by ${time}`;
  const weekday = date.toLocaleDateString("en-GB", { weekday: "short" });
  const day = date.toLocaleDateString("en-GB", { day: "numeric", month: "short" });
  return `${weekday}, ${day} by ${time}`;
}
