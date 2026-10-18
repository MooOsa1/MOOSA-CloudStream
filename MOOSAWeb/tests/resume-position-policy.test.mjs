import test from 'node:test';
import assert from 'node:assert/strict';
import {compatibleResumeTime} from '../public/resume-position-policy.js';

test('same feature runtime can resume safely at the viewer position',()=>{
  assert.equal(compatibleResumeTime({
    position:2718.49,oldDuration:4981,newDuration:4981}),2718.4);
  assert.equal(compatibleResumeTime({
    position:90.38,oldDuration:6564,newDuration:6565}),90.3);
});
test('never seek unrelated shorts or different cuts, near ends, bad durations',()=>{
  for(const pair of [
    {position:400,oldDuration:6564,newDuration:440},
    {position:400,oldDuration:6564,newDuration:5900},
    {position:400,oldDuration:6564,newDuration:7300},
    {position:4,oldDuration:6564,newDuration:6564},
    {position:6556,oldDuration:6564,newDuration:6564},
    {position:NaN,oldDuration:6564,newDuration:6564},
    {position:90,oldDuration:Infinity,newDuration:6564},
    {position:90,oldDuration:6564,newDuration:NaN},
    {position:90,oldDuration:0,newDuration:6564},
    {position:90,oldDuration:6564,newDuration:1100}
  ])assert.equal(compatibleResumeTime(pair),null,JSON.stringify(pair));
});
test('accept slight encoding padding but reject meaningful edition mismatch',()=>{
  assert.equal(compatibleResumeTime({
    position:400,oldDuration:4981,newDuration:5040}),400);
  assert.equal(compatibleResumeTime({
    position:400,oldDuration:4981,newDuration:5300}),null);
});
