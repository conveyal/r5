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
  request({url: `${url}query.json`, gzip: true}, (err, res, body) => {
    let query = JSON.parse(body)
    console.dir(query)
    request({url: `${url}stop_trees.dat`, encoding: null, gzip: true}, (err, res, body) => {
      let stopTreeCache = body
      console.log(`Stop tree cache retrieved, ${Math.round(stopTreeCache.length / 1000)}kb uncompressed`)

      request({url: `${url}${x}/${y}.dat`, encoding: null, gzip: true}, (err, res, body) => {
        let origin = body
        console.log(`Origin data retrieved, ${Math.round(origin.length / 1000)}kb uncompressed`)

        //write(query, stopTreeCache, origin, x, y, which, stream)
      })
    })
  })
}

/**
 * Get a travel time surface and accessibility results for a particular origin.
 * Pass in references to the query (the JS object stored in query.json), the stopTreeCache, the origin file, the
 * x and y coordinates of the origin relative to the query, what parameter you want (BEST_CASE, WORST_CASE or AVERAGE),
 * and a grid. Returns a travel time/accessibility surface which can be used by isochoroneTile and accessibilityForCutoff
 */
export function getSurface (query, stopTreeCache, origin, originX, originY, which, grid) {
  let ret = new Uint8Array(query.width * query.height)

  // where is the transit portion of the origin data
  // there are a certain number of pixels in each direction aroudn the origin with times in them. read the radius, multiply by two to get diameter,
  // add one because there is a pixel in the center, square to get number of pixels, multipl by two because these are two-byte values,
  // and add two to skip the initial two-byte value specifying radius (phew).
  let transitOffset = Math.pow(origin[0] * 2 + 1, 2) + 1

  // how many departure minutes are there
  // skip number of stops
  let nMinutes = origin[transitOffset + 1]

  let travelTimes = new Uint8Array(nMinutes)
  // store the accessibility at each departure minute for every possible cutoff 1 - 120
  let accessPerMinute = new Float64Array(nMinutes * 120)

  // x and y refer to pixel coordinates not origins here
  // loop over rows first
  for (let y = 0, pixelIdx = 0, stcOffset = 0; y < query.height; y++) {
    for (let x = 0; x < query.width; x++, pixelIdx++) {
      let nStops = stopTreeCache[stcOffset++]

      // fill with unreachable
      travelTimes.fill(255)

      for (let stopIdx = 0; stopIdx < nStops; stopIdx++) {
        // read the stop ID
        let stopId = stopTreeCache[stcOffset++]

        // read the distance
        let distance = stopTreeCache[stcOffset++]

        //console.log(`stop ${stopId} at distance ${distance} (${nStops} stops to consider)`)

        // de-delta-code times
        let previous = 0
        for (let minute = 0; minute < nMinutes; minute++) {
          let offset = transitOffset + 2 + stopId * nMinutes + minute
          let travelTimeToStop = origin[offset] + previous
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

      // compute access value
      // get value of this pixel from grid
      let gridx = x + query.west - grid.west
      let gridy = y + query.north - grid.north

      // if condition below fails we're off the grid, value is zero, don't bother with calculations
      if (gridx >= 0 && gridx < grid.width && gridy >= 0 && gridy < grid.height) {
        let val = grid.data[gridy * grid.width + gridx]

        for (let minute = 0; minute < nMinutes; minute++) {
          let travelTime = travelTimes[minute]

          if (travelTime === 255) continue

          // put this in all of the correct cutoff categories for this minute
          for (let cutoff = 119; cutoff >= travelTime - 1; cutoff--) {
            // TODO roll off smoothly
            accessPerMinute[cutoff * nMinutes + minute] += val
          }
        }
      }
    }
  }

  return {
    surface: ret,
    access: accessPerMinute,
    nMinutes: nMinutes
  }
}

/** Get the cumulative accessibility number for a cutoff from a travel time surface */
export function accessibilityForCutoff (surface, cutoff, which) {
  let accessibility = surface.access.slice(cutoff * surface.nMinutes, (cutoff + 1) * surface.nMinutes)

  if (which === 'BEST_CASE') return accessibility.reduce(Math.max) | 0
  else if (which === 'WORST_CASE') return accessibility.reduce(Math.min) | 0
  else if (which === 'AVERAGE') return (accessibility.reduce((a, b) => a + b) / surface.nMinutes) | 0
}

/** draw a tile onto the canvas. First three options are same as those used in leaflet TileLayer */
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

  // compiler should avoid overflow checks for xp and yp because of the < 256 condition, but prevent it from checking for
  // pixel overflow with | 0
  for (let yp = 0, pixel = 0; yp < 256; yp++) {
    for (let xp = 0; xp < 256; xp++, pixel = (pixel + 1) | 0) {
      // figure out where xp and yp fall on the surface
      let xpsurf = (xp / scaleFactor + xoff) | 0
      let ypsurf = (yp / scaleFactor + yoff) | 0
      
      let val
      if (xpsurf < 0 || xpsurf > query.width || ypsurf < 0 || ypsurf > query.height) {
        val = 255
      } else {
        val = surface.surface[ypsurf * query.width + xpsurf]
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
  request({url: `${url}query.json`, gzip: true}, (err, data, body) => {
    cb(JSON.parse(body))
  })
}

export function getStopTrees (url, cb) {
  fetch(`${url}stop_trees.dat`).then(res => res.arrayBuffer())
    .then(res => {
      let buf = new Int32Array(res)
      console.log(`Stop trees ${Math.round(buf.byteLength / 1000)}kb uncompressed`)
      cb(buf)
    })
}

/** x, y relative to query origin */
export function getOrigin (url, x, y, cb) {
  x |= 0 // round off, coerce to integer
  y |= 0
  fetch(`${url}${x}/${y}.dat`).then(res => res.arrayBuffer())
    .then(res => {
      let buf = new Int32Array(res)
      console.log(`Origin ${Math.round(buf.byteLength / 1000)}kb uncompressed`)
      cb(buf)
    })
}

/** download a grid */
export function getGrid (url, category, cb) {
  fetch(`${url}grids/${category}.grid`).then(res => res.arrayBuffer())
    .then(res => {
      console.log(`Grid ${res.length / 1000}kb uncompressed`)

      // de-delta-code
      // skip header in data
      let arr = new Float64Array(res, 24)

      for (let i = 0, prev = 0; i < arr.length; i++) {
        arr[i] = (prev += arr[i])
      }

      let dv = new DataView(res)
      cb({
        // parse header
        zoom: dv.getInt32(0, true),
        west: dv.getInt32(4, true),
        north: dv.getInt32(8, true),
        width: dv.getInt32(12, true),
        height: dv.getInt32(16, true),
        data: arr
      })
    })
}


/** main
let url = process.argv[2]
let x = parseInt(process.argv[3])
let y = parseInt(process.argv[4])
let png = process.argv[5]
let stream = fs.createWriteStream(png)
writeStaticImage(x, y, url, 'AVERAGE', stream)*/
