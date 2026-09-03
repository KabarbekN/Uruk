#!/usr/bin/env node
import { cli } from '../shared/runtime.mjs';
import { descriptor, analyze } from './analyzer.mjs';

process.exitCode = cli(descriptor, analyze);
