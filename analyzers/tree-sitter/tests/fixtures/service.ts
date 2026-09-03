import { external } from './dependency';
export interface Request { amount: number; }
export class Service {
  create(input: Request): number {
    const text = 'class Imaginary {}';
    if (input.amount < 500) throw new Error(text);
    return external(input.amount);
  }
}
