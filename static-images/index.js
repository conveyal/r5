/**
 * Create static travel time images from static site output.
 */

import request from 'browser-request'

/**
 * Write a static image for the origin pixels x and y to the stream stream. which can be BEST, WORST or AVERAGE.
 * bucket is the S3 bucket in which the results are stored. Prefix is the prefix within that bucket.
 *
 * TODO: make this asynchronous.
 */
function writeStaticImage (x, y, url, which, stream) {
  console.log('requesting stop tree cache')
  // TODO error handling
  // TODO callback mess
  request({url: `${url}/query.json`, gzip: true}, (err, res, body) => {
    let query = JSON.parse(body)
    console.dir(query)
    request({url: `${url}/stop_trees.dat`, encoding: null, gzip: true}, (err, res, body) => {
      let stopTreeCache = body
      console.log(`Stop tree cache retrieved, ${Math.round(stopTreeCache.length / 1000)}kb uncompressed`)

      request({url: `${url}/${x}/${y}.dat`, encoding: null, gzip: true}, (err, res, body) => {
        let origin = body
        console.log(`Origin data retrieved, ${Math.round(origin.length / 1000)}kb uncompressed`)

        //write(query, stopTreeCache, origin, x, y, which, stream)
      })
    })
  })
}

export function getSurface (query, stopTreeCache, origin, originX, originY, which) {
  let ret = new Uint8Array(query.width * query.height)

  // where is the transit portion of the origin data
  // there are a certain number of pixels in each direction aroudn the origin with times in them. read the radius, multiply by two to get diameter,
  // add one because there is a pixel in the center, square to get number of pixels, multipl by two because these are two-byte values,
  // and add two to skip the initial two-byte value specifying radius (phew).
  let transitOffset = Math.pow(origin.readInt16LE(0) * 2 + 1, 2) * 2 + 2

  // how many departure minutes are there
  // skip number of stops
  let nMinutes = origin.readInt16LE(transitOffset + 4)

  let travelTimes = new Uint8Array(nMinutes)

  // x and y refer to pixel coordinates not origins here
  // loop over rows first
  for (let y = 0, pixelIdx = 0, stcOffset = 0; y < query.height; y++) {
    for (let x = 0; x < query.width; x++, pixelIdx++) {
      let nStops = stopTreeCache.readInt16LE(stcOffset)

      stcOffset += 2 // skip the bytes with the number of stops

      // fill with unreachable
      travelTimes.fill(255)

      for (let stopIdx = 0; stopIdx < nStops; stopIdx++) {
        // read the stop ID
        let stopId = stopTreeCache.readInt32LE(stcOffset)
        stcOffset += 4

        // read the distance
        let distance = stopTreeCache.readInt16LE(stcOffset)
        stcOffset += 2

        //console.log(`stop ${stopId} at distance ${distance} (${nStops} stops to consider)`)

        // de-delta-code times
        let previous = 0
        for (let minute = 0; minute < nMinutes; minute++) {
          let offset = transitOffset + 6 + stopId * nMinutes * 2 + minute * 2
          let travelTimeToStop = origin.readInt16LE(offset) + previous
          previous = travelTimeToStop

          if (travelTimeToStop === -1) continue

          let travelTimeToPixel = travelTimeToStop + distance

          travelTimeToPixel /= 60
          travelTimeToPixel |= 0 // convert to int
          if (travelTimeToPixel > 254) continue

          if (travelTimes[minute] > travelTimeToPixel) travelTimes[minute] = travelTimeToPixel
        }
      }

      // compute value for pixel
      let pixel
      if (which === 'BEST_CASE') {
        pixel = 255
        for (let i = 0; i < nMinutes; i++) {
          pixel = Math.min(pixel, travelTimes[i])
        }
      } else if (which === 'AVERAGE') {
        let sum = 0
        let count = 0

        for (let i = 0; i < nMinutes; i++) {
          if (travelTimes[i] !== 255) {
            sum += travelTimes[i]
            count++
          }
        }

        // coerce to int
        if (count > nMinutes / 2) pixel = (sum / count) | 0
        else pixel = 255
      } else if (which === 'WORST_CASE') {
        pixel = 0
        for (let i = 0; i < nMinutes; i++) {
          pixel = Math.max(pixel, travelTimes[i])
        }
      }

      // set pixel value
      ret[pixelIdx] = pixel
    }
  }

  return ret
}

export function isochroneTile (canvas, tilePoint, zoom, query, surface, cutoffMinutes) {
  // find top-left coords at zoom 10
  let xoff = tilePoint.x * 256
  let yoff = tilePoint.y * 256

  let scaleFactor = Math.pow(2, zoom - query.zoom)

  // NB hipsters would use bitshifts but bitwise operators in Javascript only work on 32-bit ints. Javascript does not
  // have 64-bit integer types.
  xoff = Math.round(xoff / scaleFactor)
  yoff = Math.round(yoff / scaleFactor)

  xoff -= query.west
  yoff -= query.north

  // NB x and y offsets are now relative to query

  let ctx = canvas.getContext('2d')
  let data = ctx.createImageData(256, 256)

  for (let yp = 0, pixel = 0; yp < 256; yp++) {
    for (let xp = 0; xp < 256; xp++, pixel++) {
      // figure out where xp and yp fall on the surface
      let xpsurf = Math.round(xp / scaleFactor) + xoff
      let ypsurf = Math.round(yp / scaleFactor) + yoff
      
      let val
      if (xpsurf < 0 || xpsurf > query.width || ypsurf < 0 || ypsurf > query.height) {
        val = 255
      } else {
        val = surface[ypsurf * query.width + xpsurf]
      }

      if (val <= cutoffMinutes) {
        // 50% transparent yellow (#ddddaa)
        data.data[pixel * 4] = 0xdd
        data.data[pixel * 4 + 1] = 0xdd
        data.data[pixel * 4 + 2] = 0xaa
        data.data[pixel * 4 + 3] = 220
      } else {
        // fully transparent
        data.data[pixel * 4 + 3] = 0
      }
    }
  }

  ctx.putImageData(data, 0, 0)
}

export function getQuery (url, cb) {
  request({url: `${url}/query.json`, gzip: true}, (err, data, body) => {
    cb(JSON.parse(body))
  })
}

export function getStopTrees (url, cb) {
  fetch(`${url}/stop_trees.dat`).then(res => res.arrayBuffer())
    .then(res => {
      let buf = new Buffer(res)
      console.log(`Stop trees ${Math.round(buf.byteLength / 1000)}kb uncompressed`)
      cb(buf)
    })
}

/** x, y relative to query origin */
export function getOrigin (url, x, y, cb) {
  x |= 0 // round off, coerce to integer
  y |= 0
  fetch(`${url}/${x}/${y}.dat`).then(res => res.arrayBuffer())
    .then(res => {
      let buf = new Buffer(res)
      console.log(`Origin ${Math.round(buf.byteLength / 1000)}kb uncompressed`)
      cb(buf)
    })
}

/** main
let url = process.argv[2]
let x = parseInt(process.argv[3])
let y = parseInt(process.argv[4])
let png = process.argv[5]
let stream = fs.createWriteStream(png)
writeStaticImage(x, y, url, 'AVERAGE', stream)*/
