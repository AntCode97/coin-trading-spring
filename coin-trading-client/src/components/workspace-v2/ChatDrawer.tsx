import type { ReactNode } from 'react';

interface ChatDrawerProps {
  open: boolean;
  title: string;
  onClose: () => void;
  children: ReactNode;
}

export function ChatDrawer({ open, title, onClose, children }: ChatDrawerProps) {
  return (
    <div className={`workspace-chat-drawer ${open ? 'open' : ''}`} aria-hidden={!open}>
      <button type="button" className="workspace-chat-backdrop" onClick={onClose} aria-label="채팅 닫기" />
      <aside className="workspace-chat-panel">
        <header>
          <strong>{title}</strong>
          <button type="button" onClick={onClose}>닫기</button>
        </header>
        <div className="workspace-chat-content">
          {children}
        </div>
      </aside>
    </div>
  );
}
