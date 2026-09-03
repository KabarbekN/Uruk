export function discount(amount: number): number {
  if (amount < 500) throw new Error('Minimum');
  return amount * 0.9;
}

export class OrderService {
  create(amount: number): number {
    return discount(amount);
  }
}
