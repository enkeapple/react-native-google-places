require 'json'

package = JSON.parse(File.read(File.join(__dir__, './package.json')))

Pod::Spec.new do |s|
  s.name           = 'react-native-google-places'
  s.version        = package['version']
  s.summary        = package['description']
  s.description    = package['description']
  s.license        = package['license']
  s.author         = package['author']
  s.homepage       = 'https://github.com/vovkasm/react-native-google-places'
  s.source         = { :git => 'https://github.com/vovkasm/react-native-google-places.git', :tag => s.version }

  s.platform       = :ios, '8.0'

  s.source_files   = 'ios/*.{h,m}'

  s.dependency 'React'
  s.dependency 'GooglePlaces'
  s.dependency 'GoogleMaps'
end
