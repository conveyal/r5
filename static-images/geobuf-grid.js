/**
 * Convert a GeoBuf file to a regular grid
 * Usage: geobuf-grid.js [--png] geobuf.pbf output_prefix.
 * Creates one .grid file for each numeric attribute in the geobuf. If --png is specified, creates log-scaled pngs as well (useful for debugging).
 */

import grid from './grid'
import geobuf from 'geobuf'
import Pbf from 'pbf'
import fs from 'fs'
import zlib from 'zlib'
import Canvas from 'canvas'

const ZOOM = 10

// http://stackoverflow.com/questions/5767325
function remove (arr, val) {
  let idx = arr.indexOf(val)

  if (idx !== -1) {
    arr.splice(idx, 1)
    return true
  }

  return false
}

let args = process.argv.slice(2)
let png = remove(args, '--png') || remove(args, '-p')

// load the geobuf
// ugh I didn't want to load the whole thing to memory, but oh well
let buff = fs.readFileSync(args[0])
let pbf = new Pbf(buff)
let gb = geobuf.decode(pbf)

// free memory
pbf = undefined
buff = undefined

console.log(`read ${gb.features.length} features`)
let grids = grid(gb, ZOOM)
console.log(`made grids for ${grids.size} opportunity categories`)

if (png) console.log('writing pngs')

let categories = {}

grids.forEach((arrbuf, name) => {
  let buff = new Buffer(arrbuf)

  // sanitize name
  let fn = name.replace(/[^a-zA-Z0-9\-_]/g, '_')
  categories[name] = fn

  zlib.gzip(buff, (err, gzipped) => fs.writeFile(args[1] + fn + '.grid', gzipped, () => {}))

  if (png) writePng(arrbuf, args[1] + fn + '.png')
})

fs.writeFile(args[1] + 'categories.json', JSON.stringify(categories), () => {})

function writePng (arrbuf, file) {
  let dv = new DataView(arrbuf)
  let width = dv.getInt32(12, true)
  let height = dv.getInt32(16, true)

  let cvs = new Canvas(width, height)
  let ctx = cvs.getContext('2d')
  let data = ctx.createImageData(width, height)

  let array = new Float64Array(arrbuf, 24)

  // first figure out the maximum value
  let max = 1

  let curr = 0

  for (let i = 0; i < array.length; i++) {
    curr += array[i]
    // take a log because there are often order-of-magnitude differences in job density across a region, this avoids creating
    // a black png with a few white spots.
    max = Math.max(Math.log(curr + 1), max)
  }

  // now write the image
  curr = 0
  for (let i = 0; i < array.length; i++) {
    curr += array[i]
    // take log and clamp, see comment above
    let val = (Math.log(curr + 1) / max * 0xff) & 0xff

    data.data[i * 4] = val
    data.data[i * 4 + 1] = val
    data.data[i * 4 + 2] = val
    data.data[i * 4 + 3] = 0xff
  }

  ctx.putImageData(data, 0, 0)

  cvs.pngStream().pipe(fs.createWriteStream(file))
}