import test from 'node:test';
import assert from 'node:assert/strict';
import {sourceLookupBudgetMs} from '../public/source-timeout-policy.js';
test('web timeout extends only for configured original JVM movie pilot',()=>{
  assert.equal(sourceLookupBudgetMs('movie',true),75000);
  assert.equal(sourceLookupBudgetMs('movie',false),16500);
  assert.equal(sourceLookupBudgetMs('tv',true),16500);
});
