(() => {
  'use strict'

  const MOBILE_QUERY = '(max-width: 767px)'
  const COLUMN_ROLES = ['sidebar', 'center', 'details']
  const media = window.matchMedia(MOBILE_QUERY)
  let frame = null
  let frameObserver = null
  let scheduled = false
  let swipeStart = null

  const scheduleSync = () => {
    if (scheduled) return
    scheduled = true
    requestAnimationFrame(() => {
      scheduled = false
      sync()
    })
  }

  const sidebarColumn = () => frame?.querySelector(':scope > [data-dsh-mobile-role="sidebar"]') ?? null

  const sidebarToggle = () => {
    const sidebar = sidebarColumn()
    if (sidebar === null) return null
    return [...sidebar.querySelectorAll('button[aria-label]')].find(button => {
      const label = button.getAttribute('aria-label') ?? ''
      return /sidebar|侧边栏/iu.test(label)
    }) ?? null
  }

  const toggleSidebar = open => {
    if (frame === null) return
    const isOpen = !frame.hasAttribute('data-sidebar-collapsed')
    if (open === isOpen) return
    const toggle = sidebarToggle()
    if (toggle !== null) {
      toggle.click()
      return
    }
  }

  const rightbarOpen = () => frame !== null && (
    frame.hasAttribute('data-rightbar-fullscreen') ||
    (!frame.hasAttribute('data-rightbar-collapsed') && !frame.hasAttribute('data-details-collapsed'))
  )

  const openRightbar = () => {
    if (frame === null || rightbarOpen()) return
    const toggle = [...frame.querySelectorAll('button[aria-label]')].find(button =>
      /^(Open right sidebar|打开右侧边栏)$/iu.test(button.getAttribute('aria-label') ?? ''),
    )
    toggle?.click()
  }

  const ensureControls = () => {
    if (document.body === null) return
    let scrim = document.querySelector('[data-dsh-mobile-ui-scrim]')
    if (scrim === null) {
      scrim = document.createElement('button')
      scrim.type = 'button'
      scrim.setAttribute('data-dsh-mobile-ui-scrim', '')
      scrim.setAttribute('aria-label', document.documentElement.lang.startsWith('zh') ? '关闭侧边栏' : 'Close sidebar')
      scrim.addEventListener('click', () => toggleSidebar(false))
      document.body.append(scrim)
    }
  }

  const stampPhoneControls = () => {
    const sessionLog = [...document.querySelectorAll('button[aria-busy]')]
      .find(button => /^(Session log|会话日志)$/u.test(button.textContent?.trim() ?? ''))
    sessionLog?.setAttribute('data-session-log-download', '')

    const accessMode = [...document.querySelectorAll('button[aria-label]')].find(button => {
      const label = button.getAttribute('aria-label') ?? ''
      return label.startsWith('Access mode') || label.startsWith('访问模式')
    })
    if (accessMode !== undefined) {
      accessMode.setAttribute('data-input-access-mode', '')
      const modes = accessMode.parentElement?.parentElement
      const tools = modes?.parentElement
      const toolbar = tools?.parentElement
      const trailing = toolbar?.lastElementChild
      if (modes && tools && toolbar && trailing && trailing !== tools) {
        modes.setAttribute('data-composer-modes', '')
        tools.setAttribute('data-composer-tools', '')
        toolbar.setAttribute('data-composer-toolbar', '')
        trailing.setAttribute('data-composer-trailing', '')
      }
    }

    document.querySelectorAll('textarea').forEach(textarea => {
      textarea.setAttribute('enterkeyhint', 'send')
    })
  }

  const stampSettingsPage = () => {
    document.querySelectorAll('[data-dsh-mobile-settings-open]').forEach(sidebar => {
      sidebar.removeAttribute('data-dsh-mobile-settings-open')
    })
    const dialog = [...document.querySelectorAll('[role="dialog"]')].find(candidate =>
      candidate.querySelector('[data-slot="settings.header"]') !== null,
    )
    if (dialog === undefined) return

    dialog.setAttribute('data-dsh-mobile-settings', '')
    dialog.parentElement?.setAttribute('data-dsh-mobile-settings-overlay', '')
    dialog.closest('[data-dsh-mobile-role="sidebar"]')
      ?.setAttribute('data-dsh-mobile-settings-open', '')
    const nav = dialog.querySelector(':scope > nav')
    const content = nav?.nextElementSibling ?? null
    const header = content?.firstElementChild ?? null
    const options = header?.nextElementSibling ?? null
    nav?.setAttribute('data-dsh-mobile-settings-nav', '')
    nav?.firstElementChild?.setAttribute('data-dsh-mobile-settings-title', '')
    nav?.children[1]?.setAttribute('data-dsh-mobile-settings-tabs', '')
    content?.setAttribute('data-dsh-mobile-settings-content', '')
    header?.setAttribute('data-dsh-mobile-settings-header', '')
    options?.setAttribute('data-dsh-mobile-settings-options', '')

    const sectionSlot = dialog.querySelector('[data-slot="settings.section"]')
    const section = sectionSlot?.firstElementChild ?? null
    if (section === null) return
    section.setAttribute('data-dsh-mobile-settings-section', '')

    const blocks = [...section.children].flatMap(child =>
      getComputedStyle(child).display === 'contents' ? [...child.children] : [child],
    )
    blocks.forEach(block => {
      if (!(block instanceof HTMLElement)) return
      const rowAttribute = 'data-dsh-mobile-settings-row'
      const groupAttribute = 'data-dsh-mobile-settings-group'
      let isRow = block.hasAttribute(rowAttribute)
      if (isRow) {
        block.removeAttribute(groupAttribute)
      } else if (!block.hasAttribute(groupAttribute)) {
        isRow = getComputedStyle(block).flexDirection === 'row'
        block.setAttribute(isRow ? rowAttribute : groupAttribute, '')
      }
      if (isRow && block.firstElementChild !== block.lastElementChild) {
        block.firstElementChild?.setAttribute('data-dsh-mobile-settings-copy', '')
        block.lastElementChild?.setAttribute('data-dsh-mobile-settings-control', '')
      }
      if (!isRow) {
        const choiceGrid = [...block.children].find(child =>
          child.querySelectorAll(':scope > button').length > 1,
        )
        choiceGrid?.setAttribute('data-dsh-mobile-settings-choice-grid', '')
      }
    })
  }

  const attachFrame = nextFrame => {
    if (nextFrame === frame) return
    frameObserver?.disconnect()
    frame = nextFrame
    if (frame === null) return

    frame.setAttribute('data-dsh-mobile-frame', '')
    const overlay = frame.querySelector(':scope > [data-shell-overlay]')
    let roleIndex = 0
    for (const child of frame.children) {
      if (child === overlay || roleIndex >= COLUMN_ROLES.length) break
      child.setAttribute('data-dsh-mobile-role', COLUMN_ROLES[roleIndex])
      roleIndex += 1
    }

    frameObserver = new MutationObserver(scheduleSync)
    frameObserver.observe(frame, {
      attributes: true,
      attributeFilter: ['data-sidebar-collapsed', 'data-details-collapsed', 'data-rightbar-collapsed', 'data-rightbar-fullscreen'],
    })
  }

  const syncControls = () => {
    ensureControls()
    const scrim = document.querySelector('[data-dsh-mobile-ui-scrim]')
    const mobile = media.matches
    const sidebarOpen = frame !== null && !frame.hasAttribute('data-sidebar-collapsed')
    if (scrim !== null) scrim.hidden = !mobile || !sidebarOpen
  }

  const sync = () => {
    const overlay = document.querySelector('[data-shell-overlay]')
    attachFrame(overlay?.parentElement ?? null)
    stampPhoneControls()
    stampSettingsPage()
    syncControls()
  }

  const onTouchStart = event => {
    const touch = event.touches[0]
    swipeStart = null
    if (!touch || event.touches.length !== 1 || !media.matches || frame === null ||
        !frame.hasAttribute('data-sidebar-collapsed') || rightbarOpen() ||
        document.querySelector('[role="dialog"], [aria-modal="true"]')) return
    const edge = touch.clientX <= 24 ? 'left' :
      touch.clientX >= window.innerWidth - 24 ? 'right' : null
    if (edge !== null) swipeStart = { edge, x: touch.clientX, y: touch.clientY, id: touch.identifier }
  }

  const onTouchMove = event => {
    if (swipeStart === null) return
    if (event.touches.length !== 1) {
      swipeStart = null
      return
    }
    const touch = event.touches[0]
    if (touch === undefined || touch.identifier !== swipeStart.id) return
    const deltaX = (touch.clientX - swipeStart.x) * (swipeStart.edge === 'left' ? 1 : -1)
    const deltaY = Math.abs(touch.clientY - swipeStart.y)
    if (deltaX < -8 || deltaY > 32) {
      swipeStart = null
      return
    }
    if (deltaX >= 16 && deltaX > deltaY * 1.5 && event.cancelable) event.preventDefault()
    if (deltaX >= 56 && deltaX > deltaY * 1.5) {
      const edge = swipeStart.edge
      swipeStart = null
      if (event.cancelable) event.preventDefault()
      if (edge === 'left') toggleSidebar(true)
      else openRightbar()
    }
  }

  const onSidebarClick = event => {
    if (!media.matches || frame?.hasAttribute('data-sidebar-collapsed')) return
    const target = event.target
    if (!(target instanceof Element)) return

    const sidebar = sidebarColumn()
    if (sidebar === null || !sidebar.contains(target)) return

    const button = target.closest('button[aria-label]')
    const buttonLabel = button?.getAttribute('aria-label') ?? ''
    if (button !== null && /new session|新建会话/iu.test(buttonLabel)) {
      requestAnimationFrame(() => toggleSidebar(false))
      return
    }

    const session = target.closest('[role="treeitem"][aria-selected]')
    if (session === null) return
    if (session.getAttribute('aria-selected') === 'true') return
    if (target.closest('button, a, input, select, textarea, [role="button"], [role="menuitem"]')) return

    requestAnimationFrame(() => toggleSidebar(false))
  }

  const start = () => {
    const bodyObserver = new MutationObserver(scheduleSync)
    bodyObserver.observe(document.body, { childList: true, subtree: true })
    media.addEventListener('change', scheduleSync)
    document.addEventListener('click', onSidebarClick, true)
    window.addEventListener('touchstart', onTouchStart, { passive: true })
    window.addEventListener('touchmove', onTouchMove, { passive: false })
    window.addEventListener('touchend', () => { swipeStart = null }, { passive: true })
    window.addEventListener('touchcancel', () => { swipeStart = null }, { passive: true })
    sync()
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start, { once: true })
  } else {
    start()
  }
})()
