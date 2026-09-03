import { fireEvent, render, screen } from '@testing-library/react';
import { RefreshCw } from 'lucide-react';
import { describe, expect, it, vi } from 'vitest';
import { IconButton } from '../shared/ui';

describe('IconButton tooltips', () => {
  it('does not leave a hidden tooltip in the layout and preserves the button name', () => {
    render(<IconButton icon={RefreshCw} label="Обновить результаты анализа" />);
    const button = screen.getByRole('button', {
      name: 'Обновить результаты анализа',
    });
    expect(
      screen.queryByRole('tooltip', { hidden: true }),
    ).not.toBeInTheDocument();
    fireEvent.pointerEnter(button);
    const tooltip = screen.getByRole('tooltip');
    expect(tooltip).toHaveTextContent('Обновить результаты анализа');
    expect(tooltip.parentElement).toBe(document.body);
    expect(button).toHaveAttribute('aria-describedby', tooltip.id);
    fireEvent.pointerLeave(button);
    expect(
      screen.queryByRole('tooltip', { hidden: true }),
    ).not.toBeInTheDocument();
  });

  it('keeps a focused tooltip after pointer leave and dismisses it with Escape', () => {
    const onFocus = vi.fn();
    render(<IconButton icon={RefreshCw} label="Обновить" onFocus={onFocus} />);
    const button = screen.getByRole('button', { name: 'Обновить' });
    fireEvent.pointerEnter(button);
    fireEvent.focus(button);
    fireEvent.pointerLeave(button);
    expect(onFocus).toHaveBeenCalledOnce();
    expect(screen.getByRole('tooltip')).toBeInTheDocument();
    fireEvent.keyDown(button, { key: 'Escape' });
    expect(
      screen.queryByRole('tooltip', { hidden: true }),
    ).not.toBeInTheDocument();
    expect(button).toHaveAccessibleName('Обновить');
    fireEvent.blur(button);
    fireEvent.focus(button);
    expect(screen.getByRole('tooltip')).toBeInTheDocument();
    fireEvent.blur(button);
    expect(
      screen.queryByRole('tooltip', { hidden: true }),
    ).not.toBeInTheDocument();
  });

  it('keeps modal tooltips inside the dialog top layer and removes them on unmount', () => {
    const { unmount } = render(
      <dialog open aria-label="Настройки проекта">
        <IconButton icon={RefreshCw} label="Обновить" />
      </dialog>,
    );
    fireEvent.focus(screen.getByRole('button', { name: 'Обновить' }));
    expect(screen.getByRole('tooltip').parentElement).toBe(
      screen.getByRole('dialog'),
    );
    unmount();
    expect(
      screen.queryByRole('tooltip', { hidden: true }),
    ).not.toBeInTheDocument();
  });
});
