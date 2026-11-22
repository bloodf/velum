import { test, expect } from '@playwright/test'
import { render, routes } from '../src/entry-server'
import { docs } from '../src/content'

test('every route prerenders to markup without a browser', () => {
  expect(routes).toEqual(['/', ...docs.map((doc) => `/docs/${doc.slug}`)])
  const home = render('/')
  for (const text of ['EVERY WORD', 'Private by default.', 'Get the app.', 'Plaud Note Pro', 'Read the privacy policy']) {
    expect(home, text).toContain(text)
  }
  for (const doc of docs) {
    const html = render(`/docs/${doc.slug}`)
    expect(html, doc.slug).toContain(doc.title.replace('&', '&amp;'))
  }
})
