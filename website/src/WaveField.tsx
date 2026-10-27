import { useEffect, useRef } from 'react'

const vertexShader = `
  attribute vec2 a_position;
  uniform float u_time;
  uniform vec2 u_mouse;
  varying float v_energy;
  varying float v_depth;
  void main() {
    float x = a_position.x;
    float z = a_position.y;
    float voiceA = sin(x * 5.2 + u_time * 0.75 + z * 2.0) * 0.095;
    float voiceB = sin(x * 10.0 - u_time * 0.46 - z * 3.4) * 0.032;
    float voiceC = cos(z * 7.0 + u_time * 0.35 + x * 2.0) * 0.038;
    float mouseWave = exp(-8.0 * distance(a_position, u_mouse)) * 0.13;
    float envelope = 0.35 + 0.65 * (1.0 - abs(x));
    float y = z * 0.58 + (voiceA + voiceB + voiceC + mouseWave) * envelope;
    float perspective = 0.88 + z * 0.2;
    gl_Position = vec4(x * perspective, y - 0.02, 0.0, 1.0);
    gl_PointSize = 1.15 + (z + 1.0) * 0.8;
    v_energy = abs(voiceA + voiceB) * 7.0;
    v_depth = z;
  }
`

const fragmentShader = `
  precision mediump float;
  varying float v_energy;
  varying float v_depth;
  void main() {
    float d = distance(gl_PointCoord, vec2(0.5));
    if (d > 0.5) discard;
    vec3 paper = vec3(0.12, 0.12, 0.115);
    vec3 accent = vec3(0.42, 0.42, 0.40);
    vec3 color = mix(paper, accent, smoothstep(0.24, 0.65, v_energy));
    float alpha = (0.09 + v_energy * 0.38) * (0.65 + (v_depth + 1.0) * 0.2);
    gl_FragColor = vec4(color, alpha * smoothstep(0.5, 0.1, d));
  }
`

function createShader(gl: WebGLRenderingContext, type: number, source: string) {
  const shader = gl.createShader(type)!
  gl.shaderSource(shader, source)
  gl.compileShader(shader)
  return shader
}

export default function WaveField() {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const gl = canvas.getContext('webgl', { alpha: true, antialias: true })
    if (!gl) return
    const program = gl.createProgram()!
    gl.attachShader(program, createShader(gl, gl.VERTEX_SHADER, vertexShader))
    gl.attachShader(program, createShader(gl, gl.FRAGMENT_SHADER, fragmentShader))
    gl.linkProgram(program)
    gl.useProgram(program)

    const columns = 112
    const rows = 58
    const points = new Float32Array(columns * rows * 2)
    let p = 0
    for (let y = 0; y < rows; y++) {
      for (let x = 0; x < columns; x++) {
        points[p++] = (x / (columns - 1)) * 2.5 - 1.25
        points[p++] = (y / (rows - 1)) * 2 - 1
      }
    }
    const buffer = gl.createBuffer()
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
    gl.bufferData(gl.ARRAY_BUFFER, points, gl.STATIC_DRAW)
    const position = gl.getAttribLocation(program, 'a_position')
    gl.enableVertexAttribArray(position)
    gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0)
    const timeLocation = gl.getUniformLocation(program, 'u_time')
    const mouseLocation = gl.getUniformLocation(program, 'u_mouse')
    gl.enable(gl.BLEND)
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA)

    let frame = 0
    let start = performance.now()
    const pointer = { x: 0, y: 0 }
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    const resize = () => {
      const dpr = Math.min(window.devicePixelRatio || 1, 2)
      canvas.width = canvas.clientWidth * dpr
      canvas.height = canvas.clientHeight * dpr
      gl.viewport(0, 0, canvas.width, canvas.height)
    }
    const onPointer = (event: PointerEvent) => {
      const rect = canvas.getBoundingClientRect()
      pointer.x = ((event.clientX - rect.left) / rect.width) * 2 - 1
      pointer.y = -(((event.clientY - rect.top) / rect.height) * 2 - 1)
    }
    const render = (now: number) => {
      gl.clearColor(0, 0, 0, 0)
      gl.clear(gl.COLOR_BUFFER_BIT)
      gl.uniform1f(timeLocation, reduced ? 1.5 : (now - start) / 1000)
      gl.uniform2f(mouseLocation, pointer.x, pointer.y)
      gl.drawArrays(gl.POINTS, 0, columns * rows)
      if (!reduced) frame = requestAnimationFrame(render)
    }
    resize()
    window.addEventListener('resize', resize)
    window.addEventListener('pointermove', onPointer, { passive: true })
    frame = requestAnimationFrame(render)
    return () => {
      cancelAnimationFrame(frame)
      window.removeEventListener('resize', resize)
      window.removeEventListener('pointermove', onPointer)
      gl.deleteBuffer(buffer)
      gl.deleteProgram(program)
      start = 0
    }
  }, [])
  return <canvas className="wave-field" ref={canvasRef} aria-hidden="true" />
}
