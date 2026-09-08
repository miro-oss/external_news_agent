import type { CSSProperties, ReactNode } from 'react'
import './skeleton.css'

export function Skeleton({ width, height, className = '' }: {
  width?: CSSProperties['width']
  height?: CSSProperties['height']
  className?: string
}) {
  return <span className={`skeleton-shape ${className}`} style={{ width, height }} aria-hidden="true" />
}

export function SkeletonText({ lines = 3 }: { lines?: number }) {
  return <div className="skeleton-text" aria-hidden="true">
    {Array.from({ length: lines }, (_, index) => (
      <Skeleton key={index} width={index === lines - 1 ? '68%' : index % 2 ? '92%' : '100%'} />
    ))}
  </div>
}

/** One loading announcement per region; placeholder shapes are never interactive. */
export function SkeletonRegion({ label, className = '', contentClassName, children }: {
  label: string
  className?: string
  contentClassName?: string
  children: ReactNode
}) {
  return <div className={`loading-skeleton ${className}`} role="status" aria-label={label} aria-busy="true">
    <div className={contentClassName} aria-hidden="true">{children}</div>
  </div>
}
