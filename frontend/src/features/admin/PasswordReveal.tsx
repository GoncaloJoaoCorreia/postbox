import { useState } from "react";

export function PasswordReveal({
  label,
  email,
  password,
  onDismiss,
}: {
  label: string;
  email: string;
  password: string;
  onDismiss: () => void;
}) {
  const [copied, setCopied] = useState(false);

  async function handleCopy() {
    try {
      await copyText(password);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  }

  return (
    <div className="password-reveal">
      <p>
        {label} for <strong>{email}</strong> — shown once, copy it now.
      </p>
      <code className="password-value">{password}</code>
      <button type="button" className="btn-ghost btn-small" onClick={handleCopy}>
        {copied ? "Copied" : "Copy"}
      </button>
      <button type="button" className="btn-ghost btn-small" onClick={onDismiss}>
        Dismiss
      </button>
    </div>
  );
}

async function copyText(text: string) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
    return;
  }
  const area = document.createElement("textarea");
  area.value = text;
  document.body.appendChild(area);
  area.select();
  document.execCommand("copy");
  document.body.removeChild(area);
}
