import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { NodeList } from '../features/semantic-canvas/NodeList';
import { useLocale } from '../shared/lib/i18n';
import { rule } from './fixtures';

const nodes = Array.from({ length: 101 }, (_, index) => ({
  ...rule,
  id: `node-${index}`,
  label: `Policy ${index + 1}`,
}));
beforeEach(() => useLocale.getState().setLocale('en'));

it('bounds the node list to 50 rows and preserves selection across pages', async () => {
  const select = vi.fn();
  render(<NodeList nodes={nodes} onSelect={select} />);
  expect(screen.getAllByRole('row')).toHaveLength(51);
  expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled();
  await userEvent.click(screen.getByRole('button', { name: 'Next page' }));
  expect(screen.getByRole('button', { name: 'Policy 51' })).toBeVisible();
  await userEvent.click(screen.getByRole('button', { name: 'Next page' }));
  expect(screen.getAllByRole('row')).toHaveLength(2);
  expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled();
  await userEvent.click(screen.getByRole('button', { name: 'Policy 101' }));
  expect(select).toHaveBeenCalledWith(nodes[100]);
});

it('returns to the first page when the bounded projection changes', async () => {
  const { rerender } = render(<NodeList nodes={nodes} onSelect={vi.fn()} />);
  await userEvent.click(screen.getByRole('button', { name: 'Next page' }));
  rerender(<NodeList nodes={nodes.slice(0, 60)} onSelect={vi.fn()} />);
  expect(screen.getByRole('button', { name: 'Policy 1' })).toBeVisible();
  expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled();
});
