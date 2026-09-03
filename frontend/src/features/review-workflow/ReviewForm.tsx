import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { CheckCheck } from 'lucide-react';
import { z } from 'zod';
import { api } from '../../shared/api/client';
import type { ReviewInput, SemanticNode } from '../../shared/api/types';
import { Button, ErrorState, Field } from '../../shared/ui';
import { notify } from '../../shared/ui/notifications';
import { useT, type TranslationKey } from '../../shared/lib/i18n';

const decisions: { value: ReviewInput['decision']; key: TranslationKey }[] = [
  { value: 'CONFIRMED', key: 'confirm' },
  { value: 'REJECTED', key: 'reject' },
  { value: 'EDITED', key: 'edit' },
  { value: 'MERGED', key: 'merge' },
  { value: 'MARKED_TECHNICAL', key: 'markTechnical' },
  { value: 'NEEDS_REVIEW', key: 'needsReview' },
];

export function ReviewForm({ node }: { node: SemanticNode }) {
  const { t } = useT();
  const client = useQueryClient();
  const [decision, setDecision] =
    useState<ReviewInput['decision']>('CONFIRMED');
  const [comment, setComment] = useState('');
  const [title, setTitle] = useState(node.label);
  const [description, setDescription] = useState(node.subtitle ?? '');
  const [mergeTarget, setMergeTarget] = useState('');
  const validMerge =
    decision !== 'MERGED' ||
    (z.string().uuid().safeParse(mergeTarget.trim()).success &&
      mergeTarget.trim() !== node.id);
  const mutation = useMutation({
    mutationFn: () =>
      api.review(node.id, {
        decision,
        comment: comment.trim(),
        editedTitle: decision === 'EDITED' ? title.trim() : '',
        editedDescription: decision === 'EDITED' ? description.trim() : '',
        ...(decision === 'MERGED' ? { mergeTargetId: mergeTarget.trim() } : {}),
      }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['review-queue'] });
      void client.invalidateQueries({ queryKey: ['node', node.id] });
      void client.invalidateQueries({ queryKey: ['canvas'] });
      notify(t('reviewSaved'));
      setComment('');
    },
  });
  return (
    <form
      className="review-form"
      onSubmit={(event) => {
        event.preventDefault();
        if (validMerge) mutation.mutate();
      }}
    >
      <Field label={t('reviewDecision')}>
        <select
          value={decision}
          onChange={(event) =>
            setDecision(event.target.value as ReviewInput['decision'])
          }
        >
          {decisions.map((item) => (
            <option key={item.value} value={item.value}>
              {t(item.key)}
            </option>
          ))}
        </select>
      </Field>
      {decision === 'EDITED' && (
        <>
          <Field label={t('editedTitle')}>
            <input
              required
              value={title}
              onChange={(event) => setTitle(event.target.value)}
            />
          </Field>
          <Field label={t('editedDescription')}>
            <textarea
              required
              rows={3}
              value={description}
              onChange={(event) => setDescription(event.target.value)}
            />
          </Field>
        </>
      )}
      {decision === 'MERGED' && (
        <Field label={t('mergeTarget')}>
          <input
            required
            value={mergeTarget}
            onChange={(event) => setMergeTarget(event.target.value)}
            spellCheck={false}
            aria-invalid={!validMerge && Boolean(mergeTarget)}
          />
        </Field>
      )}
      <Field label={t('comment')}>
        <textarea
          rows={3}
          value={comment}
          onChange={(event) => setComment(event.target.value)}
        />
      </Field>
      {mutation.isError && <ErrorState compact error={mutation.error} />}
      <Button
        type="submit"
        variant="primary"
        busy={mutation.isPending}
        disabled={
          !validMerge ||
          (decision === 'EDITED' && (!title.trim() || !description.trim()))
        }
      >
        <CheckCheck size={15} />
        {t('submitReview')}
      </Button>
    </form>
  );
}
