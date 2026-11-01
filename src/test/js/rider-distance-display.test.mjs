import assert from 'node:assert/strict';
import test from 'node:test';

import {formatPickupDistance} from '../../main/resources/static/rider-web/distance-display.mjs';

test('formats pickup distances below one kilometer in meters', () => {
    assert.equal(formatPickupDistance(0), '取货距离 0 米');
    assert.equal(formatPickupDistance(0.0246), '取货距离 25 米');
    assert.equal(formatPickupDistance(0.999), '取货距离 999 米');
});

test('formats pickup distances of one kilometer or more in kilometers', () => {
    assert.equal(formatPickupDistance(1), '取货距离 1.00 公里');
    assert.equal(formatPickupDistance(2.345), '取货距离 2.35 公里');
});

test('null, absent, and invalid pickup distances are shown as unknown', () => {
    assert.equal(formatPickupDistance(null), '取货距离未知');
    assert.equal(formatPickupDistance(undefined), '取货距离未知');
    assert.equal(formatPickupDistance(''), '取货距离未知');
    assert.equal(formatPickupDistance('  '), '取货距离未知');
    assert.equal(formatPickupDistance(-0.1), '取货距离未知');
    assert.equal(formatPickupDistance(Number.NaN), '取货距离未知');
    assert.equal(formatPickupDistance(Number.POSITIVE_INFINITY), '取货距离未知');
    assert.equal(formatPickupDistance('invalid'), '取货距离未知');
    assert.equal(formatPickupDistance(true), '取货距离未知');
});
