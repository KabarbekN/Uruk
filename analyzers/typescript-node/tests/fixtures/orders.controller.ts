import { Controller as Api, Post, UseGuards } from '@nestjs/common';
import { Min, IsInt } from 'class-validator';
import { OrderService } from './service';

export class OrderRequest {
  @IsInt()
  @Min(500)
  amount!: number;
}

@Api('orders')
@UseGuards('authenticated')
export class OrderController {
  constructor(private readonly service: OrderService) {}

  @Post()
  create(request: OrderRequest): number {
    return this.service.create(request.amount);
  }
}
