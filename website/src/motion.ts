export const EASE_OUT = [0.23, 1, 0.32, 1] as const
export const EASE_IN_OUT = [0.77, 0, 0.175, 1] as const
export const EASE_DRAWER = [0.32, 0.72, 0, 1] as const

export const DUR = {
  press: 0.12,
  fast: 0.16,
  base: 0.2,
  slow: 0.3,
  hero: 1,
} as const

export const transitions = {
  fast: { duration: DUR.fast, ease: EASE_OUT },
  base: { duration: DUR.base, ease: EASE_OUT },
  slow: { duration: DUR.slow, ease: EASE_OUT },
  hero: { duration: DUR.hero, ease: EASE_OUT },
} as const
