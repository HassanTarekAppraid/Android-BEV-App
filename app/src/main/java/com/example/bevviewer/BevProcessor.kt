package com.example.bevviewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.*

/**
 * BevProcessor.kt
 * ================
 * Pure Kotlin implementation of the FB-SSEM 360° BEV pipeline.
 * No Python, no server, no network — runs entirely on the Jetson Nano.
 *
 * Algorithm:
 *   For every pixel in the output BEV canvas:
 *     1. Compute its real-world ground position (x_fwd, y_right) in metres
 *     2. For each of the 4 cameras:
 *        a. Transform world point into camera frame  X_cam = R @ (P_world - t_cam)
 *        b. Project through Mei fisheye model        (u, v) = meiProject(X_cam)
 *        c. Sample colour from fisheye image at (u,v) via bilinear interpolation
 *        d. Compute blend weight = cos^1.5(angle) × (1/distance)
 *     3. Weighted average of all camera contributions → output pixel
 *
 * Camera model:  Mei Unified Projection (ξ = 1.7634)
 * Intrinsics:    fx=fy=331, cx=cy=256, image 512×512
 * Source:        FB-SSEM / F2BEV paper (arXiv:2303.03651)
 */
object BevProcessor {

    // ─── Camera Intrinsics ──────────────────────────────────────────────────
    // From: computeNormalizedReferencePoints.py → flcw_unity.yml
    private const val XI    = 1.7634f
    private const val FX    = 331.0f
    private const val FY    = 331.0f
    private const val CX    = 256.0f
    private const val CY    = 256.0f
    private const val IMG_W = 512
    private const val IMG_H = 512

    // ─── Camera Extrinsics ─────────────────────────────────────────────────
    private const val CAM_HEIGHT = 1f   // metres above ground

    private val CAM_YAWS = mapOf(
        "front" to 0.toFloat(),
        "right" to (PI / 2).toFloat(),
        "rear"  to PI.toFloat(),
        "left"  to (3 * PI / 2).toFloat()
    )

    private val CAMS = listOf("front", "left", "rear", "right")

    // ─── BEV Canvas ────────────────────────────────────────────────────────
    const val BEV_W        = 500
    const val BEV_H        = 500
    const val SCALE        = 0.02f
    const val RANGE_X_MAX  =  5.0f
    const val RANGE_X_MIN  = -5.0f
    const val RANGE_Y_MIN  = -5.0f

    // ─── Car body lateral offset ────────────────────────────────────────────
    // The rear camera is mounted at x=0.132 m to the right of the rear-axle
    // centreline. Shifting the overlay box by the same amount aligns it with
    // the actual car body visible in the BEV ground texture.
    private const val CAR_Y_OFFSET = 1.3f   // metres → positive = shift right

    // ───────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ───────────────────────────────────────────────────────────────────────

    fun buildBev(images: Map<String, Bitmap>): Bitmap {
        val bevR   = FloatArray(BEV_H * BEV_W)
        val bevG   = FloatArray(BEV_H * BEV_W)
        val bevB   = FloatArray(BEV_H * BEV_W)
        val totalW = FloatArray(BEV_H * BEV_W)

        // Pre-read all camera pixels into int arrays for fast access
        val camPixels = mutableMapOf<String, IntArray>()
        for (cam in CAMS) {
            val bmp = images[cam] ?: continue
            val scaled = if (bmp.width != IMG_W || bmp.height != IMG_H)
                Bitmap.createScaledBitmap(bmp, IMG_W, IMG_H, true) else bmp
            val pixels = IntArray(IMG_W * IMG_H)
            scaled.getPixels(pixels, 0, IMG_W, 0, 0, IMG_W, IMG_H)
            camPixels[cam] = pixels
        }

        // Pre-build rotation matrices
        val rotations = mutableMapOf<String, FloatArray>()
        for ((cam, yaw) in CAM_YAWS) {
            rotations[cam] = buildRotationMatrix(yaw)
        }

        // ── Main loop ───────────────────────────────────────────────────
        for (row in 0 until BEV_H) {
            val worldX = RANGE_X_MAX - row * SCALE

            for (col in 0 until BEV_W) {
                val worldY = RANGE_Y_MIN + col * SCALE
                val idx    = row * BEV_W + col

                for (cam in CAMS) {
                    val pixels = camPixels[cam] ?: continue
                    val R      = rotations[cam]  ?: continue
                    val yaw    = CAM_YAWS[cam]!!

                    val dx = worldX
                    val dy = worldY
                    val dz = CAM_HEIGHT

                    val xc = R[0]*dx + R[1]*dy + R[2]*dz
                    val yc = R[3]*dx + R[4]*dy + R[5]*dz
                    val zc = R[6]*dx + R[7]*dy + R[8]*dz

                    // Mei projection
                    val r3d  = sqrt(xc*xc + yc*yc + zc*zc) + 1e-9f
                    val mzXi = zc / r3d + XI
                    if (mzXi < 1e-5f) continue

                    val u = FX * (xc / r3d) / mzXi + CX
                    val v = FY * (yc / r3d) / mzXi + CY

                    if (u < 0f || u >= IMG_W - 1f || v < 0f || v >= IMG_H - 1f) continue

                    // Blend weight
                    val dist  = sqrt(worldX*worldX + worldY*worldY) + 1e-6f
                    val cosA  = (cos(yaw)*worldX + sin(yaw)*worldY) / dist
                    if (cosA <= 0f) continue
                    val weight = cosA.pow(1.5f) / dist.coerceIn(0.1f, 15f)

                    // Bilinear sample
                    val u0 = u.toInt();  val v0 = v.toInt()
                    val u1 = (u0 + 1).coerceAtMost(IMG_W - 1)
                    val v1 = (v0 + 1).coerceAtMost(IMG_H - 1)
                    val du = u - u0;     val dv = v - v0

                    val p00 = pixels[v0 * IMG_W + u0]
                    val p01 = pixels[v0 * IMG_W + u1]
                    val p10 = pixels[v1 * IMG_W + u0]
                    val p11 = pixels[v1 * IMG_W + u1]

                    bevR[idx]   += weight * bilinear(Color.red(p00),   Color.red(p01),   Color.red(p10),   Color.red(p11),   du, dv)
                    bevG[idx]   += weight * bilinear(Color.green(p00), Color.green(p01), Color.green(p10), Color.green(p11), du, dv)
                    bevB[idx]   += weight * bilinear(Color.blue(p00),  Color.blue(p01),  Color.blue(p10),  Color.blue(p11),  du, dv)
                    totalW[idx] += weight
                }
            }
        }

        // ── Assemble output ─────────────────────────────────────────────
        val outPixels = IntArray(BEV_H * BEV_W) { Color.BLACK }
        for (i in 0 until BEV_H * BEV_W) {
            val w = totalW[i]
            if (w > 1e-9f) {
                outPixels[i] = Color.rgb(
                    (bevR[i] / w).toInt().coerceIn(0, 255),
                    (bevG[i] / w).toInt().coerceIn(0, 255),
                    (bevB[i] / w).toInt().coerceIn(0, 255)
                )
            }
        }

        val bev = Bitmap.createBitmap(BEV_W, BEV_H, Bitmap.Config.ARGB_8888)
        bev.setPixels(outPixels, 0, BEV_W, 0, 0, BEV_W, BEV_H)
        return drawOverlay(bev)
    }

    // ───────────────────────────────────────────────────────────────────────
    // ROTATION MATRIX
    // ───────────────────────────────────────────────────────────────────────

    private fun buildRotationMatrix(yaw: Float): FloatArray {
        val zx = cos(yaw);  val zy = sin(yaw)

        var xx = -zy;       var xy = zx;        var xz = 0f
        val xLen = sqrt(xx*xx + xy*xy + xz*xz)
        xx /= xLen;  xy /= xLen

        val yx2 = xy*0f  - 0f*xz   // y_cam = z_cam × x_cam
        val yy2 = 0f*xx  - zx*0f
        val yz2 = zx*xy  - zy*xx

        return floatArrayOf(
            xx,  xy,  xz,
            yx2, yy2, yz2,
            zx,  zy,  0f
        )
    }

    private fun bilinear(c00: Int, c01: Int, c10: Int, c11: Int,
                         du: Float, dv: Float): Float =
        c00*(1-du)*(1-dv) + c01*du*(1-dv) + c10*(1-du)*dv + c11*du*dv

    // ───────────────────────────────────────────────────────────────────────
    // OVERLAY
    //
    // Coordinate note (old KT system):
    //   worldX = forward (ahead of car), worldY = rightward
    //   toPx(xM, yM) → Pair(col, row) in screen pixels
    //   col = (yM - RANGE_Y_MIN) / SCALE
    //   row = (RANGE_X_MAX - xM)  / SCALE
    //
    // Car box offset:
    //   CAR_Y_OFFSET = 0.132m shifts both yM bounds rightward so the green
    //   rectangle sits on top of the actual car body in the BEV image.
    //   To tune: increase CAR_Y_OFFSET to move box further right, decrease to move left.
    //
    // Arrow fix:
    //   Tip is at (bx, by) which is the UPPER point (smaller row = forward).
    //   Wings must spread LEFT and RIGHT from the tip, both going DOWN (larger row).
    //   Old bug: both wings used bx-10 → sideways V.
    //   Fixed:   left wing  = (bx-10, by+10)   right wing = (bx+10, by+10)
    // ───────────────────────────────────────────────────────────────────────

    private fun drawOverlay(bev: Bitmap): Bitmap {
        val out    = bev.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint  = Paint(Paint.ANTI_ALIAS_FLAG)

        // col = (yM - RANGE_Y_MIN) / SCALE,  row = (RANGE_X_MAX - xM) / SCALE
        fun toPx(xM: Float, yM: Float) =
            Pair((yM - RANGE_Y_MIN) / SCALE, (RANGE_X_MAX - xM) / SCALE)

        // ── Car box — shifted right by CAR_Y_OFFSET ──────────────────
        // xM: -2 (rear bumper) to +2 (front bumper)  [forward axis]
        // yM: -1+offset to +1+offset                 [lateral axis, shifted right]
        val yL = -1f + CAR_Y_OFFSET   // left edge
        val yR =  1f + CAR_Y_OFFSET   // right edge
        val (x1, y1) = toPx( 2f, yL)
        val (x2, y2) = toPx(-2f, yR)
        paint.color = Color.argb(180, 40, 40, 40);  paint.style = Paint.Style.FILL
        canvas.drawRect(x1, y1, x2, y2, paint)
        paint.color = Color.rgb(0, 220, 0);  paint.style = Paint.Style.STROKE;  paint.strokeWidth = 2f
        canvas.drawRect(x1, y1, x2, y2, paint)

        // ── Arrow — centred on car (shifted by same offset) ───────────
        // Shaft: from near rear bumper (xM=-1.2) to near front bumper (xM=1.5)
        // Tip (bx,by) is the forward/upper point  → wings go DOWN-LEFT and DOWN-RIGHT
        val yCentre = 0f + CAR_Y_OFFSET
        val (ax, ay) = toPx(-1.2f, yCentre)
        val (bx, by) = toPx( 1.5f, yCentre)
        canvas.drawLine(ax, ay, bx, by, paint)
        // FIX: left wing → (bx-10, by+10),  right wing → (bx+10, by+10)
        // Both go BELOW the tip (by+10 = larger row = backward) spreading left/right
        canvas.drawLine(bx, by, bx - 10f, by + 10f, paint)
        canvas.drawLine(bx, by, bx + 10f, by + 10f, paint)

        // ── Proximity rings (2m, 4m) centred on rear-axle origin ─────
        // Origin: toPx(0,0) = col=250, row=250  (centre of 500×500 canvas)
        val (ox, oy) = toPx(0f, 1.3f)
        paint.style = Paint.Style.STROKE;  paint.strokeWidth = 1f
        for ((dist, color) in listOf(2f to Color.CYAN, 4f to Color.rgb(0, 150, 255))) {
            paint.color = color
            canvas.drawCircle(ox, oy, dist / SCALE, paint)
            paint.style = Paint.Style.FILL;  paint.textSize = 24f
            canvas.drawText("${dist.toInt()}m", ox + dist / SCALE + 4, oy, paint)
            paint.style = Paint.Style.STROKE
        }
        return out
    }
}
